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
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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

    /**
     * 扫描长度上限（格）—— ⚠️ <b>性能护栏</b>。
     *
     * <p>逐格扫描是<b>热路径</b>（每 tick、每把工具都跑），循环次数 ≈ {@code 10 × 长度}；
     * 更糟的是每一步的 {@code getBlockState} 会<b>强制加载未加载的区块</b>（I/O）。
     * 故长度必须封顶 —— 防 NBT 被改，或模组放大 {@code blockInteractionRange}。</p>
     *
     * <p>取 32 是为了与渲染剔除、{@code LivingToolHostSync.RADIUS}、
     * {@code LivingToolPlayerSync.RADIUS} 全部对齐（见 {@code docs/idea.md} §2.7）。</p>
     */
    private static final double MAX_SCAN_LENGTH = 32.0;

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
        // 通知模组"挖完了"（与上面的 START / ABORT 配对）。
        // ⚠️ 同 stopDigging 的理由：原版这个事件由客户端包触发，服务端直调不会发。
        //    face 这里用 UP 近似 —— 它只是事件数据，不参与任何判定（挖掘本身早已完成）。
        CommonHooks.onLeftClickBlock(fake, target, Direction.UP,
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
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
            // 通知模组"这次挖掘【中断】了"。
            // ⚠️ 原版是由客户端发 ABORT_DESTROY_BLOCK 包、服务端在包处理里触发该事件；
            //    而我们走的是"服务端直调"路径，那条【不会自己发】——
            //    只听 STOP/ABORT 做收尾的模组（进度条、统计、防作弊）会漏掉，故手动补齐。
            //    与 frashStart 那边的 START 成对（2026-09-21）。
            CommonHooks.onLeftClickBlock(fake, previous.target(), Direction.UP,
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
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

        LivingToolFakePlayer fake = LivingToolFakePlayerCache.get(level, LivingItemManager.getToolOwner(tool));
        fake.setPos(origin.x, origin.y, origin.z);
        fake.setOnGround(true);
        ItemStack held = tool.copy();
        fake.equipTool(held);   // L46：同时同步附魔属性，否则效率附魔不生效

        // ⭐ 让 FakePlayer "看着"记忆射线的方向 —— 法杖 / 枪械几乎都读玩家视线。
        //    少了这一步，它们会朝默认朝向（或上一个工具的朝向）施放。
        faceTarget(fake, origin, end);

        if (target != null) {
            // ── 分支 A：右键【方块】（原版 ServerPlayerGameMode#useItemOn）──────
            BlockState state = level.getBlockState(target);
            if (!ray.matches(state.getBlock())) {
                return null;   // L4 / L8：类型不匹配即停（去皮后 Block 改变，天然停止）
            }

            Vec3 delta = end.subtract(origin);
            Direction face = delta.lengthSqr() < 1.0E-6
                ? Direction.UP
                : Direction.getNearest(-delta.x, -delta.y, -delta.z);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), face, target, false);

            // 让模组能拦截（与挖掘侧一致）
            if (CommonHooks.onRightClickBlock(fake, InteractionHand.MAIN_HAND, target, hit).isCanceled()) {
                return null;
            }

            UseOnContext context = new UseOnContext(fake, InteractionHand.MAIN_HAND, hit);
            if (!held.useOn(context).consumesAction()) {
                return null;
            }
        } else {
            // ── 分支 B：右键【空气 / 物品】（原版 ServerPlayerGameMode#useItem）──
            // ⭐ 铁魔法等模组的法杖挂在这一层（PlayerInteractEvent.RightClickItem）。
            //    原版流程：先 post 事件，未取消再 stack.use(...) —— 两步都补上（2026-09-22）。
            //    ⚠️ 这两个事件【互相独立】：右键方块走 A、右键空气走 B，原版就是这样二选一。
            InteractionResult cancelResult =
                CommonHooks.onItemRightClick(fake, InteractionHand.MAIN_HAND);
            if (cancelResult != null) {
                return null;   // 被模组取消
            }

            InteractionResultHolder<ItemStack> used =
                held.use(level, fake, InteractionHand.MAIN_HAND);
            if (!used.getResult().consumesAction()) {
                return null;
            }
            held = used.getObject();   // use 可能换掉栈（消耗 / 变身）
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

    // ------------------------------------------------------------------
    // 攻击记忆回放（活武器 —— 见 {@code docs/idea.md} §1）
    // ------------------------------------------------------------------

    /**
     * 回放<b>攻击</b>记忆 —— 沿记忆射线找生物 → 摆朝向 → 推进冷却 → 出手。
     *
     * <p>⭐ <b>活武器不实现任何攻击逻辑</b>：这里只负责「代玩家出手」，
     * 打多少伤害、触发什么效果全由 {@code fake.attack(entity)} 走原版管线决定
     * （锋利 / 击退 / 火焰附加 / 横扫 / 暴击 / 耐久<b>自动生效</b>）。</p>
     *
     * <p>⚠️ <b>冷却必须手动推进</b>（{@code W4}，同 {@code L27}）：
     * {@code FakePlayer#tick()} 是空实现 ⇒ 原版的 {@code attackStrengthTicker} 不会自增，
     * 而 {@code Player#attack} 里伤害是 {@code f *= 0.2F + f²*0.8F}（f = 冷却比例）
     * ⇒ 不推进就<b>永远只有 20% 伤害</b>。推进方式见 {@link LivingToolFakePlayer#setAttackStrengthScale}。</p>
     *
     * @param weapon 活武器（不会被本方法修改）
     * @param attack 攻击记忆；{@code null} = 无记忆
     * @param origin 射线起点（宿主位置）
     * @return 攻击后的武器副本（可能扣耐久）；{@code null} = 本次未出手、无需写回
     */
    @Nullable
    public static ItemStack replayAttack(ItemStack weapon,
                                         @Nullable LivingToolMemory.AttackMemory attack,
                                         Vec3 origin, ServerLevel level, long now) {
        if (attack == null) {
            return null;
        }

        Vec3 end = attack.endpointFrom(origin);

        LivingToolFakePlayer fake =
            LivingToolFakePlayerCache.get(level, LivingItemManager.getToolOwner(weapon));
        fake.setPos(origin.x, origin.y, origin.z);
        fake.setOnGround(true);
        ItemStack held = weapon.copy();
        fake.equipTool(held);   // 同步附魔属性，否则锋利等不生效
        // ⭐ 让 FakePlayer 看向目标 —— 与法杖/枪械同款需求，且影响击退方向
        faceTarget(fake, origin, end);

        EntityHitResult hit = findAttackTarget(attack, origin, end, fake, level);
        if (hit == null) {
            return null;
        }

        // ── 攻击冷却（S1-a：按【物品攻击速度属性】算，与原版同源）───────────────
        float cooldown = fake.getAttackCooldownTicks();
        LivingToolAction last = LivingItemManager.getToolLastAction(weapon);
        // 没打过（或记录随重启丢了）⇒ 视为冷却已满，允许立刻出手
        long elapsed = last == null ? (long) cooldown : now - last.tick();
        fake.setAttackStrengthScale((float) elapsed / cooldown);
        if (fake.getAttackStrengthScale(0.0F) < 1.0F) {
            return null;   // 还在冷却中，本次不出手
        }

        fake.attack(hit.getEntity());

        // 记下本次出手的 tick（下次算冷却用）+ 顺带驱动客户端的"动作"动画。
        // ⚠️ 两端都要写：held 是上面 copy 的副本，写回槽位用的是它。
        LivingToolAction action = new LivingToolAction(now, null);
        LivingItemManager.setToolLastAction(weapon, action);
        LivingItemManager.setToolLastAction(held, action);
        return held;
    }

    /**
     * 沿攻击记忆射线找目标生物（{@code D2}：用官方 {@code ProjectileUtil}）。
     *
     * <p>⭐ <b>不能隔墙攻击</b>：先沿射线查有没有方块，方块比实体更近 ⇒ 放弃。
     * 否则会出现"隔着墙把后面的怪打死"（实体检测是不看方块的）。</p>
     *
     * <p>⭐ <b>能打什么</b>见 {@link #isAttackableByLivingWeapon} —— 那里显式收窄了目标
     * （非生物 / 玩家 / 盔甲架 / 主人的宠物一律不打）。</p>
     */
    @Nullable
    private static EntityHitResult findAttackTarget(LivingToolMemory.AttackMemory attack, Vec3 origin,
                                                    Vec3 end, LivingToolFakePlayer fake, ServerLevel level) {
        Vec3 delta = end.subtract(origin);
        if (delta.lengthSqr() < 1.0E-6) {
            return null;
        }
        // 与录制端一致地截断扫描长度（防模组放大后扫出超远目标）
        double length = Math.min(delta.length(), MAX_SCAN_LENGTH);
        Vec3 to = origin.add(delta.normalize().scale(length));

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
            level,
            fake,
            origin,
            to,
            fake.getBoundingBox().inflate(length),
            e -> isAttackableByLivingWeapon(e, fake));

        if (hit == null) {
            return null;
        }

        // 隔墙检测：射线上更近处有方块 ⇒ 打不到
        BlockPos blocker = scanForTarget(origin, end, Set.of(), level);
        if (blocker != null
            && origin.distanceToSqr(Vec3.atCenterOf(blocker)) < origin.distanceToSqr(hit.getLocation())) {
            return null;
        }

        // R2：蹲下录过生物类型的话，类型不匹配就不打（与挖掘侧 L4/L8 同款终止条件）
        if (!attack.matches(hit.getEntity().getType())) {
            return null;
        }
        return hit;
    }

    /**
     * 活武器<b>可以打哪些目标</b> —— 显式收窄，不能只靠 {@code isAttackable()}。
     *
     * <p>⭐ <b>为什么必须自己判</b>：{@code Entity#isAttackable()} <b>默认返回 {@code true}</b>
     * （只有掉落物等极少数 override 成 {@code false}）⇒ 光靠它<b>几乎挡不住任何东西</b>。
     * 少了下面的判据，活剑会去砍<b>盔甲架、玩家的船与矿车</b>。</p>
     *
     * <ul>
     *   <li>{@code LivingEntity} —— 排除船 / 矿车 / 掉落物这类<b>非生物</b></li>
     *   <li>排除 {@code Player} —— <b>含主人</b>：宿主常常就是玩家本人，<b>绝不能误伤</b>。
     *       ⭐ 这一条连带排除了 {@code fake} 自己与旁观者（FakePlayer 也是 {@code Player} 子类），
     *       故<b>不需要</b>再单独判 {@code e != fake} / {@code !e.isSpectator()}</li>
     *   <li>排除 {@code ArmorStand} —— 它是 {@code LivingEntity} 且 {@code isAttackable()} 为 true</li>
     *   <li>排除<b>主人驯服的宠物</b> —— 否则会打自己的狼 / 猫</li>
     * </ul>
     *
     * <p>⚠️ 由此可得一个<b>硬约束</b>：活武器<b>不支持 PVP</b> —— 所有玩家一律不打。
     * 将来要放开，必须先定义"敌对关系"判据（谁算敌人）。</p>
     */
    private static boolean isAttackableByLivingWeapon(Entity entity, LivingToolFakePlayer fake) {
        if (!(entity instanceof LivingEntity) || entity instanceof Player
            || entity instanceof ArmorStand) {
            return false;
        }
        if (entity instanceof OwnableEntity owned && fake.getUUID().equals(owned.getOwnerUUID())) {
            return false;   // 主人自己驯服的宠物
        }
        return entity.isAttackable();
    }

    /**
     * 让 FakePlayer <b>看向</b>记忆射线的方向。
     *
     * <p>⭐ <b>为什么必须这一句</b>：法杖 / 枪械这类模组的物品，几乎都是读
     * {@code player.getLookAngle()}（或 {@code getViewVector}）来决定朝哪施放 ——
     * 只 {@code setPos} 不设朝向的话，它们会朝 FakePlayer 当前那套残留朝向施放。</p>
     *
     * <p>由 {@code LivingEntity#calculateViewVector} 的公式反解：</p>
     * <pre>
     *   x = −sin(yRot)·cos(xRot)
     *   y = −sin(xRot)
     *   z =  cos(yRot)·cos(xRot)
     * </pre>
     */
    private static void faceTarget(LivingToolFakePlayer fake, Vec3 origin, Vec3 end) {
        Vec3 delta = end.subtract(origin);
        if (delta.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 dir = delta.normalize();
        double y = dir.y;
        if (y > 1.0) {
            y = 1.0;
        } else if (y < -1.0) {
            y = -1.0;
        }
        float xRot = (float) -Math.toDegrees(Math.asin(y));
        float yRot = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        fake.setXRot(xRot);
        fake.setYRot(yRot);
        fake.setYHeadRot(yRot);   // 部分模组读的是头部朝向
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
        double rawLength = delta.length();
        if (rawLength < 1.0E-6) {
            return null;
        }
        // ⚠️ 只截断【扫描距离】，方向仍用【原始长度】归一化（保证单位向量）
        double length = Math.min(rawLength, MAX_SCAN_LENGTH);
        Vec3 dir = delta.scale(1.0 / rawLength);
        Vec3 to = origin.add(dir.scale(length));

        // ⭐ 用【原版的体素遍历】(BlockGetter#traverseBlocks)，而不是自己按固定步长采样：
        //    官方每次循环跳到【下一个格子边界】⇒ 复杂度 = 穿过的格子数；
        //    自己用固定步长（0.1 格）则同一格会被重复访问约 10 次 —— 慢一个数量级，且不更准。
        //
        //    ⭐ tester 返回 null 就继续下一格 ⇒ 黑名单（宿主自己）照样能塞进来，
        //       所以这次换成官方算法【没有】丢掉我们的特殊需求。
        return BlockGetter.traverseBlocks(origin, to, level, (lv, pos) -> {
            if (blacklist.contains(pos)) {
                return null;   // 宿主自己：直接无视，继续往外找
            }
            if (isOpenSpace(lv, pos)) {
                return null;   // 空气 / 纯流体：不是挖掘目标
            }
            // ⭐ 形状求交 —— 只有射线【真的穿过该格的形状】才算命中。
            //    少了这一步，只按"格子非空气"判定会出错：非满高方块（半砖 / 台阶 / 楼梯）
            //    只占格子的下半，而射线可能从它的【上半格空气部分】穿过。
            //    这正是"客户端 clip 画得出线、服务端却当成命中半砖"的分歧来源。
            //    ⚠️ 必须与客户端可视化用同一套判据（客户端走 level.clip = 形状求交）。
            VoxelShape shape = lv.getBlockState(pos).getShape(lv, pos);
            return shape.clip(origin, to, pos) != null ? pos.immutable() : null;
        }, lv -> null);   // 走完全程都没命中 ⇒ null
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
