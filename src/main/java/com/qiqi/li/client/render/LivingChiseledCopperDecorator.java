package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.domain.redstone.LivingCopperFunction;
import com.qiqi.li.living.domain.redstone.LivingCutCopperData;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.math.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * 活雕文铜块的物品装饰器，在物品栏中叠加旋转后的输入/输出方向箭头。
 *
 * <p>设计思路与活漏斗装饰器相同：使用两个箭头纹理，
 * 通过旋转 0/90/180/270 组合出 4 个方向。
 * <ul>
 *   <li>chiseled_copper_input.png - 输入箭头（默认朝上）</li>
 *   <li>chiseled_copper_output.png - 输出箭头（默认朝上）</li>
 * </ul>
 *
 * <p>渲染层次（从底到顶）：
 * <pre>
 * 1. 雕文铜块基础纹理（由 LivingIconSpec 提供）
 * 2. 输入箭头（由本 Decorator 叠加，旋转后）
 * 3. 输出箭头（由本 Decorator 叠加，旋转后）
 * </pre>
 */
public class LivingChiseledCopperDecorator implements IItemDecorator {

    private static final ResourceLocation ARROW_IN = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/chiseled_copper_input.png");

    private static final ResourceLocation ARROW_OUT = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/chiseled_copper_output.png");

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        if (!LivingCopperFunction.isChiseled(stack.getItem())) return false;

        LivingCutCopperData data = LivingItemManager.getCutCopperData(stack);
        if (data == null) return false;

        int inputRot = directionToRotation(data.inputDir());
        int outputRot = directionToRotation(data.outputDir());

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(xOffset, yOffset, 200);

        drawRotatedIcon(guiGraphics, ARROW_IN, inputRot);
        drawRotatedIcon(guiGraphics, ARROW_OUT, outputRot);

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