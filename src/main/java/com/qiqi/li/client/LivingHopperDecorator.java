package com.qiqi.li.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.LivingHopperFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.math.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * 活漏斗的物品装饰器，在物品栏中叠加旋转后的方向箭头。
 *
 * <p>这是活漏斗图标系统的第三层（箭头叠加层）。IItemDecorator 只在物品栏/快捷栏中生效，
 * 不影响手持和地面渲染。
 *
 * <p>设计思路：活漏斗有 12 种传输方向预设（4 输入方向 × 4 输出方向的组合），
 * 不可能为每种组合画一张图。因此只使用两个箭头纹理：
 * <ul>
 *   <li>hopper_arrow_in.png - 输入箭头（默认朝上）</li>
 *   <li>hopper_arrow_out.png - 输出箭头（默认朝上）</li>
 * </ul>
 * 通过旋转 0°/90°/180°/270° 组合出 4 个方向，叠加后即可表示所有传输模式。
 *
 * <p>渲染层次（从底到顶）：
 * <pre>
 * 1. hopper_base.png（由 ContextAwareHopperModel 提供，GUI 场景）
 * 2. 输入箭头（由本 Decorator 叠加，旋转后）
 * 3. 输出箭头（由本 Decorator 叠加，旋转后）
 * </pre>
 */
public class LivingHopperDecorator implements IItemDecorator {

    /** 输入箭头纹理，默认朝上（0°），旋转后可表示 4 个方向 */
    private static final ResourceLocation ARROW_IN = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/hopper_arrow_in.png");

    /** 输出箭头纹理，默认朝上（0°），旋转后可表示 4 个方向 */
    private static final ResourceLocation ARROW_OUT = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/hopper_arrow_out.png");

    /**
     * 在物品栏中渲染方向箭头。
     *
     * @param guiGraphics GUI 图形上下文
     * @param font        字体渲染器（本方法未使用）
     * @param stack       当前物品栈
     * @param xOffset     物品在槽位中的 X 偏移
     * @param yOffset     物品在槽位中的 Y 偏移
     * @return true 表示已渲染叠加层，false 表示跳过
     */
    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!LivingItemManager.isLivingItem(stack)) return false;

        var dirState = LivingHopperFunction.readDirectionState(stack);
        if (dirState == null) return false;

        int srcX = dirState.getInt("src_x", Integer.MIN_VALUE);
        int srcY = dirState.getInt("src_y", Integer.MIN_VALUE);
        int tgtX = dirState.getInt("tgt_x", Integer.MIN_VALUE);
        int tgtY = dirState.getInt("tgt_y", Integer.MIN_VALUE);

        if (srcX == Integer.MIN_VALUE || tgtX == Integer.MIN_VALUE) return false;

        Pos2D source = new Pos2D(srcX, srcY);
        Pos2D target = new Pos2D(tgtX, tgtY);

        int inputRot = directionToRotation(source);
        int outputRot = directionToRotation(target);

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        // Z=200 确保箭头绘制在物品图标之上
        poseStack.translate(xOffset, yOffset, 200);

        drawRotatedIcon(guiGraphics, ARROW_IN, inputRot);
        drawRotatedIcon(guiGraphics, ARROW_OUT, outputRot);

        poseStack.popPose();
        return true;
    }

    /**
     * 绘制旋转后的图标纹理。
     *
     * <p>旋转技巧：以图标中心 (8, 8) 为旋转轴，步骤如下：
     * <ol>
     *   <li>平移到中心 (8, 8)</li>
     *   <li>绕 Z 轴旋转指定角度</li>
     *   <li>平移回原点 (-8, -8)</li>
     *   <li>在原点绘制 16×16 的纹理</li>
     * </ol>
     *
     * @param guiGraphics GUI 图形上下文
     * @param texture     要绘制的纹理资源路径
     * @param rotation    旋转角度（0/90/180/270），0° 时直接绘制无需旋转
     */
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

    /**
     * 将方向向量映射为旋转角度。
     *
     * <p>箭头纹理默认朝上，因此：
     * <ul>
     *   <li>UP（上方）→ 0°（无需旋转）</li>
     *   <li>RIGHT（右方）→ 90°（顺时针旋转 90°）</li>
     *   <li>DOWN（下方）→ 180°</li>
     *   <li>LEFT（左方）→ 270°</li>
     * </ul>
     *
     * @param dir 方向向量
     * @return 对应的旋转角度
     */
    private static int directionToRotation(Pos2D dir) {
        if (dir.equals(Pos2D.UP)) return 0;
        if (dir.equals(Pos2D.RIGHT)) return 90;
        if (dir.equals(Pos2D.DOWN)) return 180;
        if (dir.equals(Pos2D.LEFT)) return 270;
        return 0;
    }
}