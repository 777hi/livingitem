package com.qiqi.li.living.domain.beacon;

import java.util.List;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
            int pyramidLevel = BeaconPyramid.resolveLevel(heights);

            // 效果计算（纯逻辑，BeaconEffects）：生效矿石效果 + 免费再生三档
            BeaconConfig config = BeaconConfig.of(stack);
            int stackCount = stack.getCount();
            List<BeaconConfig.Entry> active =
                BeaconEffects.activeEntries(pyramidLevel, stackCount, config);
            BeaconSegment.RegenTier regenTier =
                BeaconEffects.regenTier(pyramidLevel, stackCount, active);

            // 遥测发布（tooltip 实时行 + 未来 F3+H 面板共用）；等级每 tick 现算，不进组件
            ContainerRuntimeCache.update(context.getCacheKey(), slot,
                RuntimeSegments.EMPTY.with(BeaconSegment.INSTANCE,
                    new BeaconSegment.BeaconRuntime(pyramidLevel, heights, regenTier)));

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
                    Player owner = context.getOwnerPlayer();
                    if (owner != null) {
                        BeaconPlayerHostSync.report(owner.getUUID(), pyramidLevel);
                    }
                } else if (level.getBlockEntity(containerPos) instanceof ChestBlockEntity) {
                    List<BlockPos> lidPositions = context.getAssociatedBlockPositions();
                    // 光束起点 = 信标所在半箱（大箱按槽位落在前半/后半）
                    BlockPos beamPos = lidPositions.size() > 1
                        ? lidPositions.get((int) ((long) slot * lidPositions.size() / context.getSize()))
                        : containerPos;
                    BeaconChestHost.report(context.getCacheKey(), beamPos, lidPositions, pyramidLevel);
                }
            }
        }
    }

    /**
     * 宿主位置（规则 1：仅玩家 / 箱子）—— 无效宿主返回 {@code null}。
     *
     * <ul>
     *   <li><b>方块容器</b>（{@code getBlockPos() != null}）：只有 {@code ChestBlockEntity}
     *       算宿主（普通箱 + 陷阱箱共用该 BE）；木桶 / 潜影盒 / <b>末影箱</b> / 漏斗
     *       等一律不是 —— 注意末影箱虽然 {@code getOwnerPlayer()} 有值，但它锚在方块上，
     *       故先按方块容器判定 ⇒ 自然被排除。</li>
     *   <li><b>非方块容器</b>：玩家背包（含末影箱以外的玩家锚定容器）取主人位置；
     *       掉落物容器无主人 ⇒ null。</li>
     * </ul>
     */
    private static Vec3 resolveHostPosition(ContainerContext context, Level level) {
        BlockPos containerPos = context.getBlockPos();
        if (containerPos != null) {
            return level.getBlockEntity(containerPos) instanceof ChestBlockEntity
                ? Vec3.atCenterOf(containerPos) : null;
        }
        Player owner = context.getOwnerPlayer();
        return owner != null ? owner.position() : null;
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

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.status"));

        // 运行时数据（等级 / 柱高 / 再生档）—— 遥测不写组件，tooltip 必须走运行时缓存
        RuntimeSegments runtimeData = LivingItemClientCache.getCurrentTooltipData();
        BeaconSegment.BeaconRuntime rt = runtimeData.get(BeaconSegment.INSTANCE);
        if (rt != null) {
            if (rt.level() == 0) {
                tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.inactive"));
            } else {
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.beacon.level",
                    rt.level(),
                    String.format("%.0f", BeaconPyramid.rangeBlocks(rt.level()))));

                if (rt.level() < BeaconPyramid.MAX_LEVEL) {
                    int[] deficits = BeaconPyramid.deficitsToNext(rt.level(), rt.columnHeights());
                    long blocks = BeaconPyramid.blocksToNext(rt.level(), rt.columnHeights());
                    StringBuilder shortColumns = new StringBuilder();
                    for (int c = 0; c < BeaconPyramid.COLUMNS; c++) {
                        if (deficits[c] > 0) {
                            if (!shortColumns.isEmpty()) {
                                shortColumns.append("、");
                            }
                            shortColumns.append(BeaconPyramid.columnLabel(c)).append(" 差").append(deficits[c]);
                        }
                    }
                    tooltipAdder.accept(Component.translatable(
                        "tooltip.livingitem.beacon.pyramid", blocks, shortColumns.toString()));
                } else {
                    // 免费再生（满级才有意义）—— 三档由 BeaconEffects 现算
                    tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.regen",
                        Component.translatable(switch (rt.regenTier()) {
                            case II -> "tooltip.livingitem.beacon.regen.ii";
                            case I -> "tooltip.livingitem.beacon.regen.i";
                            case NONE -> "tooltip.livingitem.beacon.regen.none";
                        })));
                }
            }
        }

        // 静态层：效果配置账本（读组件，不依赖遥测）
        BeaconConfig config = BeaconConfig.of(stack);
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.config_title"));
        if (config.entries().isEmpty()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.config_empty"));
        } else {
            int index = 1;
            for (BeaconConfig.Entry entry : config.entries()) {
                boolean locked = rt != null && entry.ore().unlockTier > rt.level();
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.beacon.config_entry",
                    index++,
                    Component.translatable(entry.ore().effectKey()),
                    entry.amplified() ? "II" : "I",
                    Component.translatable(entry.ore().ingot.getDescriptionId()),
                    locked ? Component.translatable("tooltip.livingitem.beacon.config_locked")
                           : Component.empty()));
            }
        }
    }
}
