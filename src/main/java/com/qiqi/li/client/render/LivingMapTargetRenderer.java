package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class LivingMapTargetRenderer {

    private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");

    private static final int COLOR_DECO_HIT = 0xFFFFAA00;
    private static final int COLOR_EXPLORED = 0xFF00FF00;
    private static final int COLOR_UNEXPLORED = 0xFFFF3333;

    private LivingMapTargetRenderer() {}

    /**
     * 按命中状态选择准心颜色，3D 与 GUI 两条渲染路径共用。
     */
    private static int pickColor(boolean explored, boolean decoHit) {
        if (decoHit) return COLOR_DECO_HIT;
        return explored ? COLOR_EXPLORED : COLOR_UNEXPLORED;
    }

    public static void renderMarker(Minecraft mc, MultiBufferSource buffer, PoseStack poseStack,
                                     int mapX, int mapY, boolean explored,
                                     boolean decoHit, int packedLight) {
        TextureAtlasSprite sprite = mc.getGuiSprites().getSprite(CROSSHAIR_SPRITE);
        if (sprite == null) return;

        Matrix4f matrix4f = poseStack.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.text(sprite.atlasLocation()));

        float x = (float) mapX;
        float y = (float) mapY;
        float z = -0.03F;

        float halfSize = decoHit ? 5.0F : 4.0F;

        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();

        int color = pickColor(explored, decoHit);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >> 24) & 0xFF;

        vc.addVertex(matrix4f, x - halfSize, y + halfSize, z).setColor(r, g, b, a).setUv(u0, v1).setLight(packedLight);
        vc.addVertex(matrix4f, x + halfSize, y + halfSize, z).setColor(r, g, b, a).setUv(u1, v1).setLight(packedLight);
        vc.addVertex(matrix4f, x + halfSize, y - halfSize, z).setColor(r, g, b, a).setUv(u1, v0).setLight(packedLight);
        vc.addVertex(matrix4f, x - halfSize, y - halfSize, z).setColor(r, g, b, a).setUv(u0, v0).setLight(packedLight);
    }

    /**
     * GUI 场景的十字准心。
     *
     * @param z 绘制深度，需高于扩展地图底图与装饰图标，否则会被 LEQUAL 深度测试剔除
     */
    public static void renderMarkerGui(GuiGraphics guiGraphics, float x, float y,
                                        boolean explored, boolean decoHit, float pixelSize, int z) {
        int color = pickColor(explored, decoHit);

        int cx = Math.round(x);
        int cy = Math.round(y);
        int arm = Math.max(Math.round(pixelSize), 1);

        guiGraphics.fill(cx, cy - arm, cx + 1, cy, z, color);
        guiGraphics.fill(cx - arm, cy, cx, cy + 1, z, color);
        guiGraphics.fill(cx, cy, cx + 1, cy + 1, z, color);
        guiGraphics.fill(cx + 1, cy, cx + 1 + arm, cy + 1, z, color);
        guiGraphics.fill(cx, cy + 1, cx + 1, cy + 1 + arm, z, color);
    }
}