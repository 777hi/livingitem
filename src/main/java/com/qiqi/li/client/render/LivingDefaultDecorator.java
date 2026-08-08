package com.qiqi.li.client.render;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * 默认活物品标记装饰器，在物品栏中为所有没有专门图标的活物品叠加 living.png 标记。
 *
 * <p>与模型替换方式不同，装饰器只在渲染时叠加一层纹理，不需要修改模型烘焙管线。
 * 对于已有专门图标的活物品（漏斗、熔炉等），不会叠加此标记，避免视觉冲突。
 */
public class LivingDefaultDecorator implements IItemDecorator {

    private static final ResourceLocation LIVING_ICON =
        ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/living.png");

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!LivingItemManager.isLivingItem(stack)) return false;

        guiGraphics.blit(LIVING_ICON, xOffset, yOffset, 0, 0, 16, 16, 16, 16);
        return true;
    }
}