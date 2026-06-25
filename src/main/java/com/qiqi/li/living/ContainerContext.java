package com.qiqi.li.living;

import net.minecraft.world.item.ItemStack;

public interface ContainerContext {

    int getSize();

    ItemStack getItem(int logicalSlot);

    void setItem(int logicalSlot, ItemStack stack);

    int getMaxStackSize();

    String getStableKey(int logicalSlot, String functionId);

    default boolean isValidSlot(int logicalSlot) {
        return logicalSlot >= 0 && logicalSlot < getSize();
    }
}