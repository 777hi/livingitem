package com.qiqi.li.living.container;

import java.util.Arrays;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.SlotResolver;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.domain.hopper.LivingHopperData;
import com.qiqi.li.living.domain.hopper.HopperFilterBuilder;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;

public class ContainerSnapshot {

    public record ChestSnapshot(int usedSlots, int usedBytes, boolean isFull, boolean isByteFull) {
        public static final ChestSnapshot EMPTY = new ChestSnapshot(0, 0, false, false);
    }

    public static final ContainerSnapshot EMPTY = new ContainerSnapshot(0, new int[0], new int[0], new FilterData[0], new ChestSnapshot[0], ContainerFluidData.EMPTY);

    private final int containerSize;
    private final int[] sourceOf;
    private final int[] targetOf;
    private final FilterData[] filterOf;
    private final ChestSnapshot[] chestOf;
    private final ContainerFluidData fluidData;

    private ContainerSnapshot(int containerSize, int[] sourceOf, int[] targetOf,
                              FilterData[] filterOf, ChestSnapshot[] chestOf, ContainerFluidData fluidData) {
        this.containerSize = containerSize;
        this.sourceOf = sourceOf;
        this.targetOf = targetOf;
        this.filterOf = filterOf;
        this.chestOf = chestOf;
        this.fluidData = fluidData;
    }

    public static ContainerSnapshot capture(ContainerContext context, ContainerFluidData fluidData) {
        int containerSize = context.getSize();
        int containerWidth = context.getWidth();
        int[] sourceOf = new int[containerSize];
        int[] targetOf = new int[containerSize];
        Arrays.fill(sourceOf, -1);
        Arrays.fill(targetOf, -1);

        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!LivingHopperFunction.isLivingHopper(stack)) continue;

            LivingHopperData data = LivingItemManager.getHopperData(stack);
            if (data == null) continue;

            var dir = data.direction();
            sourceOf[slot] = SlotResolver.resolve(slot, dir.sourceOffset(), containerSize, containerWidth);
            targetOf[slot] = SlotResolver.resolve(slot, dir.targetOffset(), containerSize, containerWidth);
        }

        FilterData[] filterOf = HopperFilterBuilder.buildAll(context, containerSize, sourceOf, targetOf);
        ChestSnapshot[] chestOf = buildAllChestSnapshots(context, containerSize);

        return new ContainerSnapshot(containerSize, sourceOf, targetOf, filterOf, chestOf, fluidData);
    }

    private static ChestSnapshot[] buildAllChestSnapshots(ContainerContext ctx, int containerSize) {
        ChestSnapshot[] chestOf = new ChestSnapshot[containerSize];
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = ctx.getItem(slot);
            if (LivingChestFunction.isLivingChest(stack)) {
                int usedSlots = LivingChestFunction.countUsedSlots(stack);
                int usedBytes = LivingChestFunction.getCurrentByteUsage(stack);
                boolean isFull = usedSlots >= LivingChestFunction.CHEST_SLOTS;
                boolean isByteFull = usedBytes >= LivingChestFunction.MAX_STORAGE_BYTES;
                chestOf[slot] = new ChestSnapshot(usedSlots, usedBytes, isFull, isByteFull);
            }
        }
        return chestOf;
    }

    public int getContainerSize() {
        return containerSize;
    }

    public int[] getSourceOf() {
        return sourceOf;
    }

    public int[] getTargetOf() {
        return targetOf;
    }

    public FilterData[] getFilterOf() {
        return filterOf;
    }

    public int getSourceOf(int slot) {
        return slot >= 0 && slot < containerSize ? sourceOf[slot] : -1;
    }

    public int getTargetOf(int slot) {
        return slot >= 0 && slot < containerSize ? targetOf[slot] : -1;
    }

    public FilterData getFilterOf(int slot) {
        return slot >= 0 && slot < containerSize ? filterOf[slot] : FilterData.EMPTY;
    }

    public ContainerFluidData getFluidData() {
        return fluidData;
    }

    public ChestSnapshot getChestSnapshot(int slot) {
        return slot >= 0 && slot < containerSize && chestOf[slot] != null
            ? chestOf[slot] : ChestSnapshot.EMPTY;
    }

    public ChestSnapshot[] getChestSnapshots() {
        return chestOf;
    }
}