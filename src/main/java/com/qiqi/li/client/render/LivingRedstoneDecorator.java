package com.qiqi.li.client.render;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.redstone.LivingRedstoneData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.IItemDecorator;

public class LivingRedstoneDecorator implements IItemDecorator {

    private static final int LINE_COLOR = 0xFFAA0000;
    private static final int LINE_THICKNESS = 2;

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!stack.is(Items.REDSTONE) || !LivingItemManager.isLivingItem(stack)) return false;

        LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
        byte conn = data.connections();

        if (conn == 0) return false;

        int cx = xOffset + 8;
        int cy = yOffset + 8;

        if ((conn & LivingRedstoneData.CONN_UP) != 0) {
            guiGraphics.fill(cx - 1, yOffset, cx + 1, cy, LINE_COLOR);
        }
        if ((conn & LivingRedstoneData.CONN_DOWN) != 0) {
            guiGraphics.fill(cx - 1, cy, cx + 1, yOffset + 16, LINE_COLOR);
        }
        if ((conn & LivingRedstoneData.CONN_LEFT) != 0) {
            guiGraphics.fill(xOffset, cy - 1, cx, cy + 1, LINE_COLOR);
        }
        if ((conn & LivingRedstoneData.CONN_RIGHT) != 0) {
            guiGraphics.fill(cx, cy - 1, xOffset + 16, cy + 1, LINE_COLOR);
        }

        return true;
    }
}