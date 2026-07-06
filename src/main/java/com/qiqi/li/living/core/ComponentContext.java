package com.qiqi.li.living.core;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.ContainerContext;

public record ComponentContext(
    ContainerContext containerCtx,
    int inputSlot,
    int fuelSlot,
    int outputSlot,
    Level level
) {
    public boolean hasValidInput() {
        return inputSlot >= 0 && !containerCtx.getItem(inputSlot).isEmpty();
    }

    public boolean hasValidFuel() {
        return fuelSlot >= 0 && !containerCtx.getItem(fuelSlot).isEmpty();
    }

    public boolean hasValidOutput() {
        return outputSlot >= 0;
    }
}