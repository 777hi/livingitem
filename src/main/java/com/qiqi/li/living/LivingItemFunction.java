package com.qiqi.li.living;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public interface LivingItemFunction {
    boolean canApply(ItemStack stack);
    
    void tick(ItemStack stack, int slotIndex, Container container, Level level);
    
    String getFunctionId();
}
