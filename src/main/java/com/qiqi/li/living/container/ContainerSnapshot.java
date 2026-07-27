package com.qiqi.li.living.container;

import java.util.Arrays;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.SlotResolver;
import com.qiqi.li.living.data.LivingHopperData;
import com.qiqi.li.living.function.LivingHopperFunction;

public class ContainerSnapshot {

    public static final ContainerSnapshot EMPTY = new ContainerSnapshot(0, new int[0], new int[0], ContainerFluidData.EMPTY);

    private final int containerSize;
    private final int[] sourceOf;
    private final int[] targetOf;
    private final ContainerFluidData fluidData;

    private ContainerSnapshot(int containerSize, int[] sourceOf, int[] targetOf,
                              ContainerFluidData fluidData) {
        this.containerSize = containerSize;
        this.sourceOf = sourceOf;
        this.targetOf = targetOf;
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
            if (data == null || data == LivingHopperData.DEFAULT) continue;

            var dir = data.direction();
            sourceOf[slot] = SlotResolver.resolve(slot, dir.sourceOffset(), containerSize, containerWidth);
            targetOf[slot] = SlotResolver.resolve(slot, dir.targetOffset(), containerSize, containerWidth);
        }

        return new ContainerSnapshot(containerSize, sourceOf, targetOf, fluidData);
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

    public int getSourceOf(int slot) {
        return slot >= 0 && slot < containerSize ? sourceOf[slot] : -1;
    }

    public int getTargetOf(int slot) {
        return slot >= 0 && slot < containerSize ? targetOf[slot] : -1;
    }

    public ContainerFluidData getFluidData() {
        return fluidData;
    }
}