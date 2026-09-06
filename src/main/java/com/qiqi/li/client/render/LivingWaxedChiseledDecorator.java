package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.power.LivingWaxedChiseledData;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * 活涂蜡雕文铜块（电力层移相器）的物品装饰器：在图标上叠加**输入方向**箭头。
 *
 * <p>电力层雕文只感应输入方向的边信号（与信号层二极管语义镜像），
 * 箭头指示的就是这个读取方向，方便玩家确认感应面朝向。</p>
 *
 * <p>纹理复用信号层雕文的 {@code chiseled_copper_input.png}（默认朝上，旋转 0/90/180/270
 * 组合出 4 个方向）。电力层移相器没有独立的"输出方向"概念（派生相位登记在本体槽位、
 * 由相邻雕文的输入方向读取），故只画输入箭头。</p>
 */
public class LivingWaxedChiseledDecorator implements IItemDecorator {

    private static final ResourceLocation ARROW_IN = ResourceLocation.fromNamespaceAndPath(
        LivingItem.MOD_ID, "textures/item/chiseled_copper_input.png");

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        if (!LivingWaxedCopperFunction.isWaxedChiseled(stack.getItem())) return false;

        LivingWaxedChiseledData data = LivingItemManager.getWaxedChiseledData(stack);
        if (data == null) return false;

        int inputRot = directionToRotation(data.inputDir());

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(xOffset, yOffset, 200);
        drawRotatedIcon(guiGraphics, ARROW_IN, inputRot);
        poseStack.popPose();
        return true;
    }

    private void drawRotatedIcon(GuiGraphics guiGraphics, ResourceLocation texture, int rotation) {
        if (rotation == 0) {
            guiGraphics.blit(texture, 0, 0, 0, 0, 16, 16, 16, 16);
            return;
        }

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(8, 8, 0);
        poseStack.mulPose(Axis.ZP.rotationDegrees(rotation));
        poseStack.translate(-8, -8, 0);
        guiGraphics.blit(texture, 0, 0, 0, 0, 16, 16, 16, 16);
        poseStack.popPose();
    }

    private static int directionToRotation(Pos2D dir) {
        if (dir.equals(Pos2D.UP)) return 0;
        if (dir.equals(Pos2D.RIGHT)) return 90;
        if (dir.equals(Pos2D.DOWN)) return 180;
        if (dir.equals(Pos2D.LEFT)) return 270;
        return 0;
    }
}
