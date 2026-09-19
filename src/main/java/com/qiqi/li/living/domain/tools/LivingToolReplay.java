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
        if (ray == null) {
            LivingItemManager.setToolProgress(tool, null);
            return null;
        }

        // 1) 沿记忆射线扫描（终点 = 宿主 + offset），命中第一个「非空气且不在黑名单」的方块
        Vec3 end = ray.endpointFrom(origin);
        BlockPos target = scanForTarget(origin, end, hostBlocks, level);

        if (target == null) {
            // 路径上没有外部目标 —— 若射线终点就落在宿主自身，则允许挖自己的家（{@code L42}）。
            // 触发场景：玩家录了「很短」的记忆（典型是挖头顶的方块，眼睛到方块仅约 0.4 格），
            // 回放时终点仍在宿主方块内。玩家主动为之，视为玩法而非异常。
            BlockPos endPos = BlockPos.containing(end);
            if (hostBlocks.contains(endPos) && !level.isEmptyBlock(endPos)) {
                target = endPos;
            }
        }

        if (target == null) {
            // L7：路径上没有可挖的方块就停
            LivingItemManager.setToolProgress(tool, null);
            return null;
        }

        BlockState state = level.getBlockState(target);

        if (!ray.matches(state.getBlock())) {
            // L4 / L8：蹲下记过类型但不匹配 → 停（去皮 / 耕地后天然停止正是靠这里）
            LivingItemManager.setToolProgress(tool, null);
            return null;
        }

        // 2) 进度：目标是新的就重置（L23）
        LivingToolProgress progress = LivingItemManager.getToolProgress(tool);
        boolean freshStart = progress == null || !progress.isFor(target);
        if (freshStart) {
            progress = new LivingToolProgress(target, now);
            LivingItemManager.setToolProgress(tool, progress);
        }

        // 3) 取 FakePlayer 并配置（L26 / L28）
        LivingToolFakePlayer fake = LivingToolFakePlayerCache.get(level, LivingItemManager.getToolOwner(tool));
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
                LivingItemManager.setToolProgress(tool, null);
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
            LivingItemManager.setToolProgress(tool, null);
            return null;
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

        return held;   // 调用方负责写回容器（可能已因 F3 损坏为空）
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
        BlockPos target = scanForTarget(origin, end, blacklist, level);
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

        return result.consumesAction() ? held : null;
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

        BlockPos previous = null;
        for (double t = 0.0; t <= length; t += SCAN_STEP) {
            BlockPos current = BlockPos.containing(origin.add(dir.scale(t)));
            if (current.equals(previous)) {
                continue;   // 还在同一格
            }
            previous = current;

            if (blacklist.contains(current)) {
                continue;   // 黑名单：直接无视，继续往外找
            }
            if (!level.isEmptyBlock(current)) {
                return current;
            }
        }
        return null;
    }
}
