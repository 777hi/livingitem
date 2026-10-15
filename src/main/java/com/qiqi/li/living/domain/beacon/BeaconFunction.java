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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

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

            long[] heights = readColumnHeights(context, slot);
            int pyramidLevel = BeaconPyramid.resolveLevel(heights);

            // 遥测发布（tooltip 实时行 + 未来 F3+H 面板共用）；等级每 tick 现算，不进组件
            ContainerRuntimeCache.update(context.getCacheKey(), slot,
                RuntimeSegments.EMPTY.with(BeaconSegment.INSTANCE,
                    new BeaconSegment.BeaconRuntime(pyramidLevel, heights, BeaconSegment.RegenTier.NONE)));

            // 箱子宿主（规则 1：仅玩家 / 箱子）—— 未激活（level 0）不登记 ⇒ 不开盖不同步
            BlockPos containerPos = context.getBlockPos();
            if (containerPos != null && pyramidLevel >= 1
                    && level.getBlockEntity(containerPos) instanceof ChestBlockEntity) {
                List<BlockPos> lidPositions = context.getAssociatedBlockPositions();
                // 光束起点 = 信标所在半箱（大箱按槽位落在前半/后半）
                BlockPos hostPos = lidPositions.size() > 1
                    ? lidPositions.get((int) ((long) slot * lidPositions.size() / context.getSize()))
                    : containerPos;
                BeaconChestHost.report(context.getCacheKey(), hostPos, lidPositions, pyramidLevel);
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

        // 运行时数据（等级 / 柱高）—— 遥测不写组件，tooltip 必须走运行时缓存
        RuntimeSegments runtimeData = LivingItemClientCache.getCurrentTooltipData();
        BeaconSegment.BeaconRuntime rt = runtimeData.get(BeaconSegment.INSTANCE);
        if (rt == null) {
            return;
        }

        if (rt.level() == 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.beacon.inactive"));
            return;
        }

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
        }
    }
}
