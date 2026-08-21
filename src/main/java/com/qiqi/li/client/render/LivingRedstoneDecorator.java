package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.resources.ResourceLocation;
import com.qiqi.li.living.domain.redstone.LivingRedstoneData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.neoforged.neoforge.client.IItemDecorator;

public class LivingRedstoneDecorator implements IItemDecorator {

    private static final ResourceLocation DOT_TEXTURE =
        ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/redstone_dust_dot.png");
    private static final ResourceLocation LINE_TEXTURE =
        ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/redstone_dust_line0.png");

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!stack.is(Items.REDSTONE) || !LivingItemManager.isLivingItem(stack)) return false;

        LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
        int power = data.signalStrength();
        byte conn = data.connections();

        int color = RedStoneWireBlock.getColorForPower(Mth.clamp(power, 0, 15));
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(xOffset, yOffset, 200);

        guiGraphics.setColor(r, g, b, 1.0f);

        guiGraphics.blit(DOT_TEXTURE, 0, 0, 0, 0, 16, 16, 16, 16);

        if ((conn & LivingRedstoneData.CONN_UP) != 0) {
            drawRotatedLine(guiGraphics, 0);
        }
        if ((conn & LivingRedstoneData.CONN_DOWN) != 0) {
            drawRotatedLine(guiGraphics, 180);
        }
        if ((conn & LivingRedstoneData.CONN_LEFT) != 0) {
            drawRotatedLine(guiGraphics, 270);
        }
        if ((conn & LivingRedstoneData.CONN_RIGHT) != 0) {
            drawRotatedLine(guiGraphics, 90);
        }

        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);

        poseStack.popPose();
        return true;
    }

    private void drawRotatedLine(GuiGraphics guiGraphics, int rotation) {
        if (rotation == 0) {
            guiGraphics.blit(LINE_TEXTURE, 0, 0, 0, 0, 16, 16, 16, 16);
            return;
        }

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(8, 8, 0);
        poseStack.mulPose(Axis.ZP.rotationDegrees(rotation));
        poseStack.translate(-8, -8, 0);
        guiGraphics.blit(LINE_TEXTURE, 0, 0, 0, 0, 16, 16, 16, 16);
        poseStack.popPose();
    }
}