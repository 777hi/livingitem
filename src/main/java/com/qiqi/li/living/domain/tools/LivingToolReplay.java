package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import java.util.Set;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

/**
 * 活工具记忆回放（{@code L-d}）—— 把录下的「左键 / 右键操作」在宿主身上重放一遍。
 *
 * <h3>核心流程（挖掘记忆）</h3>
 * <pre>
 *   宿主位置 + 记忆 offset → 沿射线逐格扫描（跳过黑名单）→ 命中方块？
 *     ├─ 否            → 停（{@code L7}）
 *     ├─ 类型不匹配     → 停（{@code L8}）
 *     └─ 命中           → 推进进度（原版重算式 {@code L27}）
 *                          ≥ 1 → FakePlayer 破坏（{@code L6}）
 * </pre>
 *
 * <h3>为什么用 FakePlayer</h3>
 * 破坏速度 {@code BlockState#getDestroyProgress(Player, ...)} 依赖 Player，
 * 把活工具放进 FakePlayer 主手后，<b>效率附魔 / 材质门槛 / 掉落判定全部由原版自己算</b>，
 * 我们不需要复刻任何公式（{@code L24}）。
 *
 * <h3>重要：工具副本与写回</h3>
 * 破坏会扣耐久（{@code ItemStack#mineBlock}）。为了让容器触发<b>脏槽位同步</b>，
 * 这里给 FakePlayer 一份<b>副本</b>，破坏后由调用方写回容器
 * （{@code LivingToolFunction} 会比对是否变化再决定是否写回）。
 */
public final class LivingToolReplay {

    /** 射线扫描步长（格）。0.1 对"挖哪一格"的判定足够，且性能开销可忽略。 */
    private static final double SCAN_STEP = 0.1;

    private LivingToolReplay() {
    }

    /**
     * 回放挖掘记忆（左键行为）。
     *
     * @param tool   活工具（不会被本方法修改）
     * @param ray    挖掘记忆；null = 无记忆
     * @param origin 射线起点（宿主位置：容器中心 / 玩家眼睛 / 掉落物位置）
     * @param level  服务端世界
     * @param now    世界轴时间 {@code level.getGameTime()}
     * @return 挖掘后的工具副本（可能耐久减少，甚至因 {@code F3} 损坏为空）；
     *         {@code null} 表示本次未发生破坏、无需写回
     */
    @Nullable
    public static ItemStack replayDig(ItemStack tool, @Nullable LivingToolMemory.RayMemory ray,
                                      Vec3 origin, Set<BlockPos> hostBlocks, ServerLevel level, long now) {
        // 上一 tick 的挖掘状态：任何「停止 / 换目标」的路径都要据此清理残留的破坏裂纹（L47）
        LivingToolProgress previous = LivingItemManager.getToolProgress(tool);

        // FakePlayer 提前取（走缓存）：清理裂纹需要它的实体 id ——
        // 客户端的破坏裂纹是按【实体 id】索引的，不是按方块位置
        LivingToolFakePlayer fake = LivingToolFakePlayerCache.get(level, LivingItemManager.getToolOwner(tool));

        if (ray == null) {
            stopDigging(tool, previous, level, fake);
            return null;
        }

        // 1) 沿记忆射线扫描（终点 = 宿主 + offset），命中第一个「非空气且不在黑名单」的方块
        Vec3 end = ray.endpointFrom(origin);
        BlockPos target = resolveDigTarget(ray, origin, hostBlocks, level);

        if (target == null) {
            // L7：路径上没有可挖的方块就停
            stopDigging(tool, previous, level, fake);
            return null;
        }

        BlockState state = level.getBlockState(target);

        if (!ray.matches(state.getBlock())) {
            // L4 / L8：蹲下记过类型但不匹配 → 停（去皮 / 耕地后天然停止正是靠这里）
            stopDigging(tool, previous, level, fake);
            return null;
        }

        // 2) 进度：目标是新的就重置（L23）。
        //    换目标时旧目标上的裂纹要顺手清掉，否则会永久残留（L47）。
        boolean freshStart = previous == null || !previous.isFor(target);
        LivingToolProgress progress;
        if (freshStart) {
            if (previous != null) {
                level.destroyBlockProgress(fake.getId(), previous.target(), -1);
            }
            progress = new LivingToolProgress(target, now);
            LivingItemManager.setToolProgress(tool, progress);
        } else {
            progress = previous;
        }

        // 3) 配置 FakePlayer（L26 / L28）
        fake.setPos(origin.x, origin.y, origin.z);
        fake.setOnGround(true);   // L28：不设会被原版判为"离地"→ 速度 /5
        ItemStack held = tool.copy();
        fake.equipTool(held);   // L46：同时同步附魔属性，否则效率附魔不生效

        // 4) L36：首次命中时补上「挥击前置」—— 直接调 destroyBlock 会跳过这些，
        //    模组工具的自定义效果很多挂在上面
        if (freshStart) {
            // 逐格扫描没有 BlockHitResult，这里用射线方向反推受击面、用方块中心近似命中点
            // （只影响挥击特效与附魔回调的位置，不影响挖掘判定本身）
            Vec3 dir = end.subtract(origin);
            Direction face = dir.lengthSqr() < 1.0E-6
                ? Direction.UP
                : Direction.getNearest(-dir.x, -dir.y, -dir.z);

            if (CommonHooks.onLeftClickBlock(fake, target, face,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).isCanceled()) {
                stopDigging(tool, progress, level, fake);
                return null;
            }
            state.attack(level, target, fake);
            EnchantmentHelper.onHitBlock(level, held, fake, fake, EquipmentSlot.MAINHAND,
                Vec3.atCenterOf(target), state,
                broken -> fake.onEquippedItemBroken(broken, EquipmentSlot.MAINHAND));
        }

        // 5) 推进进度（原版重算式 L27）
        float perTick = state.getDestroyProgress(fake, level, target);
        if (perTick <= 0.0F) {
            // 挖不动（基岩 / 硬度 -1）
            stopDigging(tool, progress, level, fake);
            return null;
        }

        if (freshStart) {
            // K 组动画：本次挖掘的【预计总 tick】—— 挖掘期间速度恒定，故只写这一次。
            // ⚠️ 两端都要写：held 在步骤 3 就 copy 了，只写 tool 的话写回槽位的那份没有。
            int digTicks = Math.max(1, (int) Math.ceil(1.0F / perTick));
            LivingItemManager.setToolDigTicks(tool, digTicks);
            LivingItemManager.setToolDigTicks(held, digTicks);
        }

        float total = perTick * (float) (now - progress.startTick() + 1);

        // 破坏裂纹（0-9 档）—— 服务端广播，客户端自动显示，也是动画节奏来源（K31=c）
        level.destroyBlockProgress(fake.getId(), target, (int) (total * 10.0F));

        if (total < 1.0F) {
            return null;   // 还没挖完，无需写回
        }

        // 6) 完成破坏（L6）—— 内部自动：BreakEvent → canHarvestBlock → mineBlock → 掉落
        ServerPlayerGameMode gameMode = fake.gameMode;
        gameMode.destroyBlock(target);
        level.destroyBlockProgress(fake.getId(), target, -1);
        LivingItemManager.setToolProgress(tool, null);
        // ⚠️ held 是「写进度之后」才 copy 的副本，两边的进度必须一起清 ——
        //    否则写回槽位的是带残留进度的那份，下一 tick 会误判为「已经挖了很久」而瞬间破坏
        LivingItemManager.setToolProgress(held, null);

        return held;   // 调用方负责写回容器（可能已因 F3 损坏为空）
    }

    /**
     * 停止挖掘 —— 清掉残留在旧目标上的破坏裂纹，并复位进度组件（{@code L47}）。
     *
     * <p>⚠️ <b>为什么必须自己清裂纹</b>：原版靠 {@code ServerPlayerGameMode#tick()} 里
     * 「正在挖但方块已变空气 → {@code destroyBlockProgress(-1)}」那一段收尾，
     * 而 <b>FakePlayer 的 {@code tick()} 是空实现</b>（tech 文档 §9.5）。
     * 少了这一步，只要工具挖到一半、方块被移除（玩家挖走 / 活塞推走 / 变成空气），
     * <b>破坏裂纹就会永久停在那个位置</b> —— 裂纹是 10 张固定纹理，
     * 客户端渲染时<b>并不检查</b>那个位置现在还是不是方块。</p>
     *
     * <p>本方法在 {@code replayDig} 的<b>每一条「停」的路径</b>上调用：
     * 无记忆 / 路径上没方块 / 类型不匹配 / 事件被取消 / 挖不动。</p>
     *
     * @param previous 上一 tick 的进度（据此定位要清理哪个位置）；{@code null} 表示本来就没在挖
     */
    private static void stopDigging(ItemStack tool, @Nullable LivingToolProgress previous,
                                    ServerLevel level, LivingToolFakePlayer fake) {
        if (previous != null) {
            level.destroyBlockProgress(fake.getId(), previous.target(), -1);
        }
        LivingItemManager.setToolProgress(tool, null);
    }

    /**
     * 射线检测，<b>跳过宿主自身的方块</b>。
     *
     * <p>为什么需要：容器形态下射线起点在容器方块内部，第一个命中的必然是容器自己，
     * 不跳过就会把"工具的家"挖掉。玩家形态（起点在眼睛，空气中）与掉落物形态
     * （起点在实体位置，不是方块）都不存在这个问题。</p>
     *
     * <p>大箱子占两格，故要能连续跳过多个位置；上限设 4 次防止意外死循环。</p>
     */
    /**
     * 回放交互记忆（右键行为）。
     *
     * <p><b>不节流</b>（{@code L45}）—— 曾按「每 N tick 至多一次」限流，
     * 但 {@code now % N == 0} 的取模对齐会让<b>首次交互最多等 0.5 秒</b>，手感很迟钝。
     * 改为每 tick 尝试，靠<b>天然终止</b>限速：原版 {@code ItemAbility}（去皮 / 耕地 / 铺路）
     * 都会<b>改变方块</b>，下一次扫描到的已不是同一个方块
     * （记住类型时 {@code matches} 直接失败，未记类型时 {@code useOn} 返回 PASS），于是自动停下。</p>
     *
     * <p>只调工具自身的 {@code ItemStack#useOn}（去皮 / 耕地 / 铺路等 {@code ItemAbility}），
     * <b>不</b>调方块的 {@code useItemOn} —— 避免活工具去开门、开箱子等（{@code L36} 聚焦"工具行为"）。</p>
     *
     * @return 交互后的工具副本；{@code null} 表示本次未发生交互、无需写回
     */
    @Nullable
    public static ItemStack replayUse(ItemStack tool, @Nullable LivingToolMemory.RayMemory ray,
                                      Vec3 origin, Set<BlockPos> blacklist, ServerLevel level) {
        if (ray == null) {
            return null;
        }

        Vec3 end = ray.endpointFrom(origin);
        BlockPos target = resolveUseTarget(ray, origin, blacklist, level);
        if (target == null) {
            return null;   // L8：没有可交互的方块就停
        }

        BlockState state = level.getBlockState(target);
        if (!ray.matches(state.getBlock())) {
            return null;   // L4 / L8：类型不匹配即停（去皮后 Block 改变，天然停止）
        }

        Vec3 delta = end.subtract(origin);
        Direction face = delta.lengthSqr() < 1.0E-6
            ? Direction.UP
            : Direction.getNearest(-delta.x, -delta.y, -delta.z);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), face, target, false);

        LivingToolFakePlayer fake = LivingToolFakePlayerCache.get(level, LivingItemManager.getToolOwner(tool));
        fake.setPos(origin.x, origin.y, origin.z);
        fake.setOnGround(true);
        ItemStack held = tool.copy();
        fake.equipTool(held);   // L46：同时同步附魔属性，否则效率附魔不生效

        // 让模组能拦截（与挖掘侧一致）
        if (CommonHooks.onRightClickBlock(fake, InteractionHand.MAIN_HAND, target, hit).isCanceled()) {
            return null;
        }

        UseOnContext context = new UseOnContext(fake, InteractionHand.MAIN_HAND, hit);
        InteractionResult result = held.useOn(context);

        if (!result.consumesAction()) {
            return null;
        }

        // K 组动画：交互是【瞬时】动作，没有像 LIVING_TOOL_PROGRESS 那样的持续状态可查，
        // 客户端也拿不到"交互在哪一格"（容器形态起点埋在方块里，clip 必然自命中），
        // 故由服务端把「tick + 目标格子」一并写下。
        // ⚠️ 两端都要写：held 是上面 copy 的副本，写回槽位用的是它。
        LivingToolAction action = new LivingToolAction(level.getGameTime(), target);
        LivingItemManager.setToolLastAction(tool, action);
        LivingItemManager.setToolLastAction(held, action);
        return held;
    }

    /**
     * 求<b>挖掘</b>记忆射线的命中目标 ——
     * <b>回放与客户端可视化共用的唯一判据来源</b>（{@code L48}）。
     *
     * <p>⚠️ <b>客户端不要自己重算</b>：服务端这套判据有「宿主黑名单 / 流体 / 形状求交」三层
     * （见 {@link #scanForTarget}），在客户端复刻<b>必然逐步走偏</b> —— 已实测踩过一次：
     * {@code L47} 容器起点埋在方块里，客户端 {@code clip} 立刻自命中 ⇒ 光带缩成一个点。
     * 改为<b>服务端算完把结果同步给客户端</b>，客户端只负责画。</p>
     */
    @Nullable
    public static BlockPos resolveDigTarget(@Nullable LivingToolMemory.RayMemory ray, Vec3 origin,
                                            Set<BlockPos> hostBlocks, ServerLevel level) {
        if (ray == null) {
            return null;
        }
        Vec3 end = ray.endpointFrom(origin);
        BlockPos target = scanForTarget(origin, end, hostBlocks, level);
        if (target == null) {
            // 路径上没有外部目标 —— 若射线终点就落在宿主自身，则允许挖自己的家（{@code L42}）。
            // 触发场景：玩家录了「很短」的记忆（典型是挖头顶的方块，眼睛到方块仅约 0.4 格），
            // 回放时终点仍在宿主方块内。玩家主动为之，视为玩法而非异常。
            BlockPos endPos = BlockPos.containing(end);
            if (hostBlocks.contains(endPos) && !isOpenSpace(level, endPos)) {
                target = endPos;
            }
        }
        return target;
    }

    /**
     * 求<b>交互</b>记忆射线的命中目标（同 {@link #resolveDigTarget}，但<b>不含</b>
     * {@code L42} 的"挖自己的家"兜底 —— 交互侧没有这条规则）。
     */
    @Nullable
    public static BlockPos resolveUseTarget(@Nullable LivingToolMemory.RayMemory ray, Vec3 origin,
                                            Set<BlockPos> hostBlocks, ServerLevel level) {
        if (ray == null) {
            return null;
        }
        return scanForTarget(origin, ray.endpointFrom(origin), hostBlocks, level);
    }

    /**
     * 沿记忆射线逐格扫描，返回第一个「非空气、且不在黑名单」的方块。
     *
     * <p><b>为什么不用 {@code level.clip()}</b>：clip 不支持"跳过某些方块"，
     * 而容器形态下射线起点（宿主方块中心）位于方块内部，clip 必然先命中宿主自己，
     * 于是需要"移出宿主"之类的补丁。改成逐格扫描后：
     * <ul>
     *   <li><b>黑名单直接无视</b> —— 天然支持（本方法的核心）</li>
     *   <li>起点在方块内也不受影响</li>
     *   <li>仍然是沿真实路径，<b>不会隔空挖</b></li>
     * </ul>
     * 代价是精度略低于 clip（0.1 格步进），对"挖哪一格"的判定完全够用。</p>
     *
     * @param blacklist 不受影响的方块（当前传的是宿主自身，可按需扩展）
     * @return 目标方块；{@code null} = 路径上无可挖方块（对应 {@code L7} 的"没方块就停"）
     */
    @Nullable
    private static BlockPos scanForTarget(Vec3 origin, Vec3 end, Set<BlockPos> blacklist, ServerLevel level) {
        Vec3 delta = end.subtract(origin);
        double length = delta.length();
        if (length < 1.0E-6) {
            return null;
        }
        Vec3 dir = delta.scale(1.0 / length);

        for (double t = 0.0; t < length; t += SCAN_STEP) {
            Vec3 from = origin.add(dir.scale(t));
            Vec3 to = origin.add(dir.scale(Math.min(t + SCAN_STEP, length)));
            BlockPos current = BlockPos.containing(from);

            if (blacklist.contains(current)) {
                continue;   // 黑名单（宿主自己）：直接无视，继续往外找
            }
            if (isOpenSpace(level, current)) {
                continue;   // 空气 / 纯流体：不是挖掘目标
            }
            // ⭐ 形状求交 —— 只有射线【真的穿过该格的形状】才算命中。
            //    少了这一步，只按"格子非空气"判定会出错：非满高方块（半砖 / 台阶 / 楼梯）
            //    只占格子的下半，而射线可能从它的【上半格空气部分】穿过。
            //    这正是"客户端 clip 画得出线、服务端却当成命中半砖"的分歧来源。
            //    ⚠️ 这里必须与客户端可视化用同一套判据（客户端走 level.clip = 形状求交）。
            if (level.getBlockState(current).getShape(level, current).clip(from, to, current) == null) {
                continue;
            }
            return current;
        }
        return null;
    }

    /**
     * 该位置对射线而言是否「可穿过」—— 空气，或<b>纯流体</b>（水 / 岩浆）。
     *
     * <p>⚠️ <b>不能只用 {@code level.isEmptyBlock()}</b>（它判的是 {@code isAir()}）：
     * <b>水方块不是空气</b>。掉落物泡在水里时，射线会在 {@code t=0} 就命中水、
     * 或者在水下被水体挡住，表现为<b>"在水里不挖"</b>。</p>
     *
     * <p>判据用「<b>有流体且无碰撞箱</b>」而不是「有流体」，是为了不误伤
     * <b>充水方块</b>（waterlogged 台阶 / 楼梯）—— 它们同样持有流体状态，
     * 但有碰撞箱，属于应当可挖的目标。</p>
     */
    private static boolean isOpenSpace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }
}
