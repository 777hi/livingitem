package com.qiqi.li.client.render;

import com.qiqi.li.living.domain.chest.LivingChestTooltipComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class LivingChestTooltipRenderer implements ClientTooltipComponent {

    private static final int SLOT_SIZE = 18;
    private static final int PADDING = 4;
    private static final int BOTTOM_PADDING = 4;
    private static final int BG_COLOR = 0xF0100010;
    private static final int BORDER_COLOR_A = 0x505000FF;
    private static final int BORDER_COLOR_B = 0x5028007F;

    private final List<ItemStack> items;
    private final int columns;
    private final int rows;

    public LivingChestTooltipRenderer(LivingChestTooltipComponent component) {
        this.items = component.items();
        this.columns = component.columns();
        this.rows = component.rows();
    }

    @Override
    public int getHeight() {
        return rows * SLOT_SIZE + PADDING + BOTTOM_PADDING;
    }

    @Override
    public int getWidth(Font font) {
        return columns * SLOT_SIZE + PADDING * 2;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
        int width = getWidth(font);
        int height = getHeight();

        guiGraphics.fill(x, y, x + width, y + height, BG_COLOR);

        int border = 1;
        guiGraphics.fill(x - border, y - border, x + width + border, y, BORDER_COLOR_A);
        guiGraphics.fill(x - border, y + height, x + width + border, y + height + border, BORDER_COLOR_A);
        guiGraphics.fill(x - border, y, x, y + height, BORDER_COLOR_A);
        guiGraphics.fill(x + width, y, x + width + border, y + height, BORDER_COLOR_A);

        guiGraphics.fill(x, y - border, x + width, y, BORDER_COLOR_B);
        guiGraphics.fill(x, y + height, x + width, y + height + border, BORDER_COLOR_B);
        guiGraphics.fill(x - border, y - border, x, y + height + border, BORDER_COLOR_B);
        guiGraphics.fill(x + width, y - border, x + width + border, y + height + border, BORDER_COLOR_B);

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;

            int col = i % columns;
            int row = i / columns;
            int slotX = x + PADDING + col * SLOT_SIZE + 1;
            int slotY = y + PADDING + row * SLOT_SIZE + 1;

            guiGraphics.renderItem(stack, slotX, slotY);
            guiGraphics.renderItemDecorations(font, stack, slotX, slotY);
        }
    }
}