package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.sponge.LivingSpongeData;

/**
 * 活海绵流体叠加装饰器（设计稿 §7）—— 湿态在原版湿海绵纹理上<b>全幅</b>叠加所存流体的
 * still 贴图：贴图 / 染色取 {@link IClientFluidTypeExtensions}（与 {@code ContainerFluidOverlay}
 * 同源 ⇒ 模组流体零特判）。**只表种类、不编码量**（量在 tooltip，2026-10-29 拍板：
 * 与活涂蜡铜灯同级的简单度）；干态无物可画。
 *
 * <p>alpha 取 0.55（{@code ContainerFluidOverlay} 源格同款）—— 全幅不透明会把湿海绵
 * 纹理完全盖掉，半透既表「装着什么」又保留湿态底图。</p>
 *
 * <p>z 抬 200（ ProgressBarDecorator 同族：NeoForge 代管 resetRenderState，pose 自抬）。</p>
 */
public class LivingSpongeFluidDecorator implements IItemDecorator {

    /** 与 ContainerFluidOverlay 源格同款 alpha —— 半透叠加保留湿态底图 */
    private static final float OVERLAY_ALPHA = 0.55f;

    private static final int OVERLAY_Z = 200;

    @Override
    public boolean render(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font,
                          ItemStack stack, int xOffset, int yOffset) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        LivingSpongeData data = LivingSpongeData.of(stack);
        if (!data.isWet()) return false;

        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(data.fluid());
        ResourceLocation still = ext.getStillTexture();
        if (still == null) return false;

        int tint = ext.getTintColor();
        float r = 1f, g = 1f, b = 1f;
        if (tint != -1) {
            int a = (tint >>> 24) & 0xFF;
            if (a == 0) a = 255;
            r = ((tint >> 16) & 0xFF) / 255.0f;
            g = ((tint >> 8) & 0xFF) / 255.0f;
            b = (tint & 0xFF) / 255.0f;
        }

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(xOffset, yOffset, OVERLAY_Z);
        RenderSystem.setShaderColor(r, g, b, OVERLAY_ALPHA);
        try {
            var sprite = net.minecraft.client.Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                .apply(still);
            guiGraphics.blit(0, 0, 0, 16, 16, sprite);
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            pose.popPose();
        }
        return true;   // 改过 shader color ⇒ 交还 NeoForge 重置（接口约定）
    }
}
