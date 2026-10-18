package com.qiqi.li.living.domain.beacon;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 活信标的功能实现（设计稿 {@code docs/buffer/living-beacon-design.md}）。
 *
 * <p>Phase 2：tick 读下方一行 9 列底座柱高 → {@link BeaconPyramid} 判级 →
 * 遥测（{@link BeaconSegment}）发布；tooltip 显示等级 / 范围 / 金字塔账本。
 * 箱子宿主（开盖 + 同步）属 Step 3；效果配置与 buff 施加属 Phase 3。</p>
 */
public class BeaconFunction implements LivingItemFunction {

    public static final String ID = "living_beacon";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.BEACON) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /**
     * 效果配置（5 矿石槽）归属本功能 —— 取消活化时由框架 {@code clearLivingData} 统一清除。
     *
     * <p>⚠️ 2026-10-18 修（用户实测「取消活化配置效果不会被清除」）：本域接入时漏了这份
     * 自声明 ⇒ {@code BEACON_CONFIG} 成为孤儿数据（组件还在、无人认领）⇒ 取消活化后
     * 配置原样幸存、再次活化读到脏旧值。设计稿 §2.5 定的「取消活化清空」自此才真正落地。
     * 同族的 15 个域都声明了，唯信标漏了——{@code ComponentOwnershipTest} 已把本域纳入
     * 守卫范围（名单 + 扫描两处盲区均补），漏声明会立刻红。</p>
     */
    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(BeaconComponents.BEACON_CONFIG.value());
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) {
            return;
        }

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= context.getSize()) {
                continue;
            }

            ItemStack stack = entry.stack();
            long[] heights = readColumnHeights(context, slot);
            boolean[] exists = readColumnExists(context, slot);
            int pyramidLevel = BeaconPyramid.resolveLevel(heights);

            // 效果计算（纯逻辑，BeaconEffects）：生效矿石效果 + 满级再生三档
            BeaconConfig config = BeaconConfig.of(stack);
            int stackCount = stack.getCount();
            List<BeaconConfig.Entry> active =
                BeaconEffects.activeEntries(pyramidLevel, stackCount, config);
            BeaconSegment.RegenTier regenTier =
                BeaconEffects.regenTier(pyramidLevel, stackCount, active);

            // 遥测发布（tooltip 实时行 + 9 柱小金字塔面板共用）；等级每 tick 现算，不进组件
            ContainerRuntimeCache.update(context.getCacheKey(), slot,
                RuntimeSegments.EMPTY.with(BeaconSegment.INSTANCE,
                    new BeaconSegment.BeaconRuntime(pyramidLevel, heights, exists, regenTier)));

            // 宿主（规则 1：仅玩家 / 箱子）—— 未激活 = 完全死物 ⇒ 无 buff / 无光束 / 不开盖
            Vec3 hostPos = resolveHostPosition(context, level);
            if (pyramidLevel >= 1 && hostPos != null) {
                // buff 施加：照原版 80 刻节奏（时长 (9+等级×2) 秒 ⇒ 范围内永不掉效）
                if (level.getGameTime() % 80L == 0L) {
                    applyEffects(level, hostPos, pyramidLevel, active, regenTier);
                }

                // 箱子宿主：开盖保持 + 近处同步
                BlockPos containerPos = context.getBlockPos();
                if (containerPos == null) {
                    // 玩家宿主：联机广播（自己那份客户端本机渲染，不需要发包）
                    // 世界玻璃色不随包下发（U3 方案 A：客户端本地读世界方块即可，含别人的光束）；
                    // 容器玻璃色必须下发（客户端看不到别人背包内容）
                    Player owner = context.getOwnerPlayer();
                    if (owner != null) {
                        BeaconPlayerHostSync.report(owner.getUUID(), pyramidLevel,
                            BeaconGlassColor.scanColumn(slot, context.getSize(), context.getWidth(),
                                context::getItem));
                    }
                } else if (isLiddedChest(level.getBlockEntity(containerPos))) {
                    List<BlockPos> lidPositions = context.getAssociatedBlockPositions();
                    // 光束起点 = 信标所在半箱（大箱按槽位落在前半/后半）
                    BlockPos beamPos = lidPositions.size() > 1
                        ? lidPositions.get((int) ((long) slot * lidPositions.size() / context.getSize()))
                        : containerPos;
                    // 信标同列向上的容器玻璃色（客户端不开 GUI 读不到容器内容 ⇒ 随包下发）
                    List<Integer> glassColors = BeaconGlassColor.scanColumn(
                        slot, context.getSize(), context.getWidth(), context::getItem);
                    BeaconChestHost.report(context.getCacheKey(), beamPos, lidPositions,
                        pyramidLevel, glassColors);
                }
            }
        }
    }

    /**
     * 宿主位置（规则 1：仅玩家 / 箱子）—— 无效宿主返回 {@code null}。
     *
     * <ul>
     *   <li><b>方块容器</b>（{@code getBlockPos() != null}）：只有<b>有盖子的箱子</b>算宿主——
     *       判据 {@link #isLiddedChest}（协议级 {@code LidBlockEntity}，2026-10-18 升级）：
     *       原版箱 / 陷阱箱 / 铁箱子全家自动覆盖；木桶 / 潜影盒 / 漏斗等不是。
     *       ⚠️ <b>末影箱方块压根到不了这条判定</b>：它不暴露 {@code ItemHandler.BLOCK} 能力面
     *       ⇒ 世界容器扫描（{@code processContainerAt}）发现不了它 ⇒ 其内容由
     *       {@code processEnderChest} 按<b>玩家锚定容器</b> tick（{@code EnderChestContainerContext}，
     *       键 {@code player_<uuid>_ender_chest}，positions 空）⇒ {@code getBlockPos()} 为 null
     *       ⇒ 走下面那条玩家路径（2026-10-18 用户提问「末影箱的发光逻辑」后实证更正：
     *       旧注释说它「锚在方块上被排除」是错的，真实机制是走玩家宿主）。</li>
     *   <li><b>非方块容器</b>：玩家背包 / <b>末影箱</b>等玩家锚定容器取主人位置
     *       （⇒ 末影箱里的信标 = 玩家宿主：光束从头颅顶面射出、buff 跟人走、联机广播，
     *       与放背包里完全一样；末影箱方块本身不 tick / 不开盖 / 不发光）；
     *       掉落物容器无主人 ⇒ null。</li>
     * </ul>
     */
    private static Vec3 resolveHostPosition(ContainerContext context, Level level) {
        BlockPos containerPos = context.getBlockPos();
        if (containerPos != null) {
            return isLiddedChest(level.getBlockEntity(containerPos))
                ? Vec3.atCenterOf(containerPos) : null;
        }
        Player owner = context.getOwnerPlayer();
        return owner != null ? owner.position() : null;
    }

    /**
     * 是否「有盖子的箱子」宿主 —— 判据是<b>协议</b>而非类型白名单：
     * {@code instanceof LidBlockEntity}（原版接口：有 {@code ChestLidController} +
     * 响应 {@code triggerEvent(1, ·)} 开盖协议 ⇒ 我们的 {@code blockEvent(1,1)} 开盖保持直接可用）。
     *
     * <p><b>覆盖矩阵</b>（2026-10-18 逐个核源码实证）：原版箱 / 陷阱箱 ✅、<b>铁箱子全家</b>
     * ✅（{@code AbstractIronChestBlockEntity implements LidBlockEntity}——原先为此写了反射
     * compat，现由协议判据自动覆盖，compat 已删）、木桶 ❌（{@code BarrelBlockEntity} 未实现该接口，
     * 与设计稿旧结论一致）、潜影盒 ❌（自有动画）。⇒ 未来任何实现该协议的第三方容器
     * <b>自动纳入，零维护</b>。</p>
     *
     * <p>⚠️ <b>末影箱不在本判据的射程内</b>（2026-10-18 实证更正）：它虽是 {@code LidBlockEntity}，
     * 但其容器上下文 {@code getBlockPos()} 恒为 null（末影箱方块无 {@code ItemHandler.BLOCK}
     * 能力面，内容由 {@code processEnderChest} 按玩家锚定容器 tick）⇒ 永远走不到这里，
     * 末影箱里的信标走<b>玩家宿主</b>（见 {@link #resolveHostPosition}）。故<b>不需要</b>为
     * 排除末影箱加任何特判。</p>
     */
    static boolean isLiddedChest(BlockEntity be) {
        return be instanceof LidBlockEntity;
    }

    /**
     * 施加 buff —— 照原版 {@code BeaconBlockEntity.applyEffects} 等价逻辑：
     * 范围 = {@code 等级×10+10} 的球体（AABB 撑到世界高度）；范围内所有玩家获得
     * 「生效矿石效果 + 免费再生」，时长 {@code (9+等级×2)×20}、ambient + 显示粒子。
     */
    private static void applyEffects(Level level, Vec3 hostPos, int pyramidLevel,
                                     List<BeaconConfig.Entry> active, BeaconSegment.RegenTier regenTier) {
        double range = BeaconPyramid.rangeBlocks(pyramidLevel);
        AABB area = new AABB(BlockPos.containing(hostPos)).inflate(range)
            .expandTowards(0.0, level.getHeight(), 0.0);
        int duration = BeaconEffects.buffDurationTicks(pyramidLevel);

        for (Player player : level.getEntitiesOfClass(Player.class, area)) {
            for (BeaconConfig.Entry entry : active) {
                player.addEffect(new MobEffectInstance(entry.ore().effect,
                    duration, BeaconEffects.amplifier(entry), true, true));
            }
            if (regenTier != BeaconSegment.RegenTier.NONE) {
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
                    duration, regenTier == BeaconSegment.RegenTier.II ? 1 : 0, true, true));
            }
        }
    }

    /**
     * 读信标下方一行 9 列柱高 —— 以信标所在列为中心（偏移 ±4），越出容器边界列视为 0。
     *
     * <p>⚠️ 底座材质判定走 {@link BeaconBaseBlocks}（原版 {@code BEACON_BASE_BLOCKS} tag），
     * tag 在单测环境不加载 ⇒ 材质部分由游戏内验证兜底，纯槽位数学见
     * {@link #resolveColumnSlots}（已单测）。</p>
     */
    static long[] readColumnHeights(ContainerContext context, int slot) {
        int[] targets = resolveColumnSlots(slot, context.getSize(), context.getWidth());
        long[] heights = new long[BeaconPyramid.COLUMNS];
        for (int c = 0; c < targets.length; c++) {
            if (targets[c] >= 0) {
                heights[c] = BeaconBaseBlocks.columnHeight(context.getItem(targets[c]));
            }
        }
        return heights;
    }

    /**
     * 纯槽位数学：信标下方一行 9 列的槽位下标（{@link BeaconPyramid#CENTER} = 信标本列；
     * 越出容器边界 = −1，柱高按 0 计）。
     *
     * <p>Slot 索引口径 9 列网格（背包 hotbar 第 0 行、主区 1~3 行）。</p>
     */
    static int[] resolveColumnSlots(int slot, int size, int width) {
        int[] targets = new int[BeaconPyramid.COLUMNS];
        for (int offset = -BeaconPyramid.CENTER; offset <= BeaconPyramid.CENTER; offset++) {
            targets[offset + BeaconPyramid.CENTER] =
                SlotResolver.resolve(slot, new Pos2D(offset, 1), size, width);
        }
        return targets;
    }

    /**
     * 读 9 列存在性 —— 越界列（信标贴容器边缘 ⇒ 9 列探出边界）为 {@code false}。
     *
     * <p>区分「空槽（差材料）」与「该列不存在（别填了）」：没有它，9 柱面板会把永远填不上的
     * 列显示成缺口（设计稿 §2.6.2 越界列 / 不可达兜底）。</p>
     */
    static boolean[] readColumnExists(ContainerContext context, int slot) {
        return columnExistsFrom(resolveColumnSlots(slot, context.getSize(), context.getWidth()));
    }

    /** 纯函数：槽位下标 ≥ 0 ⇒ 该列存在（单测直接喂 {@code targets}）。 */
    static boolean[] columnExistsFrom(int[] targets) {
        boolean[] exists = new boolean[BeaconPyramid.COLUMNS];
        for (int c = 0; c < targets.length && c < exists.length; c++) {
            exists[c] = targets[c] >= 0;
        }
        return exists;
    }

    /**
     * tooltip（设计稿 §2.6.1 定稿的三层分工：图标零改动 / 文本层只留面板表达不了的 /
     * 图形层管底座几何）。
     *
     * <ul>
     *   <li><b>状态行</b>（遥测缺失 ⇒ 不猜，只有静态层）：等级 + 生效数
     *       {@code min(堆叠数, 合格槽数)}——客户端本地算，与 buff 共用
     *       {@link BeaconEffects#activeEntries} 单一事实源；</li>
     *   <li><b>满级效果行</b>：{@code regenTier} 非满级恒 NONE ⇒ 只在满级出现，NONE 也明示原因；</li>
     *   <li><b>账本</b>：未激活 ⇒ 折叠成一行（U17「完全死物」）；否则逐行列
     *       <b>休眠三因</b>（档位不够 {@code 需 L{n}} / 堆叠数不够 {@code 信标不足}）；</li>
     *   <li>底座几何与差额<b>不在文本层</b>（9 柱面板已表达，双写 = 双维护）。</li>
     * </ul>
     */
    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.status"));

        BeaconSegment.BeaconRuntime rt = LivingItemClientCache.getCurrentTooltipData()
            .get(BeaconSegment.INSTANCE);
        BeaconConfig config = BeaconConfig.of(stack);
        boolean inactive = rt != null && rt.level() == 0;

        // ── 运行时层 ────────────────────────────────────────────────
        if (rt != null && rt.level() > 0) {
            List<BeaconConfig.Entry> active =
                BeaconEffects.activeEntries(rt.level(), stack.getCount(), config);
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.beacon.level",
                rt.level(), active.size(), config.size()));

            if (rt.level() >= BeaconPyramid.MAX_LEVEL) {
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.beacon.regen",
                    Component.translatable(switch (rt.regenTier()) {
                        case II -> "tooltip.livingitem.beacon.regen.ii";
                        case I -> "tooltip.livingitem.beacon.regen.i";
                        case NONE -> "tooltip.livingitem.beacon.regen.none";
                    })));
            }
        }

        // ── 静态层：效果配置 ─────────────────────────────────────────
        if (inactive) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.beacon.config_inactive", config.size()));
            if (config.size() == 0) {
                tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.config_empty"));
            }
            return;
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.config_title"));
        if (config.size() == 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.config_empty"));
            return;
        }

        List<BeaconConfig.Entry> active = rt != null
            ? BeaconEffects.activeEntries(rt.level(), stack.getCount(), config)
            : List.of();
        int index = 1;
        for (BeaconConfig.Entry entry : config.entries()) {
            Component suffix = Component.empty();
            if (rt != null && rt.level() > 0 && !active.contains(entry)) {
                suffix = entry.ore().unlockTier > rt.level()
                    ? Component.translatable("tooltip.livingitem.beacon.sleep_level",
                        entry.ore().unlockTier)
                    : Component.translatable("tooltip.livingitem.beacon.sleep_ingot",
                        requiredStacks(config, entry, rt.level()));
            }
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.beacon.config_entry",
                index++,
                Component.translatable(entry.ore().effectKey()),
                entry.amplified() ? "II" : "I",
                Component.translatable(entry.ore().ingot.getDescriptionId()),
                suffix));
        }
    }

    /**
     * 让某休眠槽生效所需的信标堆叠数（该槽在「档位合格」序列里的 1 基序号）。
     *
     * <p>休眠若非档位原因，必是 {@code min(堆叠数, 合格槽数)} 取前 N 时被截断 ⇒
     * 所需堆叠数 = 它的合格序号。tooltip 与 buff 共用 {@link BeaconEffects#activeEntries}
     * 同一口径，不会出现「tooltip 说生效、实际没 buff」。</p>
     */
    static int requiredStacks(BeaconConfig config, BeaconConfig.Entry entry, int level) {
        int ordinal = 0;
        for (BeaconConfig.Entry e : config.entries()) {
            if (e.ore().unlockTier <= level) {
                ordinal++;
                if (e.equals(entry)) {
                    return ordinal;
                }
            }
        }
        return config.size();
    }
}
