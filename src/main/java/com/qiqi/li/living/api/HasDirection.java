package com.qiqi.li.living.api;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.model.Pos2D;

public interface HasDirection {

    int getDirectionKeyCount();

    String[] getDirectionSlotNames();

    boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction);
}