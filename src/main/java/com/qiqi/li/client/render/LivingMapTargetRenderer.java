package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class LivingMapTargetRenderer {

    private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");

    private static final int COLOR_BANNER = 0xFFFFAA00;
    private static final int COLOR_TARGET = 0xFF00DDFF;
    private static final int COLOR_EXPLORED = 0xFF00FF00;
    private static final int COLOR_UNEXPLORED = 0xFFFF3333;

    private LivingMapTargetRenderer() {}

    public static void renderMarker(Minecraft mc, MultiBufferSource buffer, PoseStack poseStack,
                                     int mapX, int mapY, boolean explored,
                                     boolean bannerHit, boolean targetPointHit, int packedLight) {
        TextureAtlasSprite sprite = mc.getGuiSprites().getSprite(CROSSHAIR_SPRITE);
        if (sprite == null) return;

        Matrix4f matrix4f = poseStack.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.text(sprite.atlasLocation()));

        float x = (float) mapX;
        float y = (float) mapY;
        float z = -0.03F;

        float halfSize = (bannerHit || targetPointHit) ? 5.0F : 4.0F;

        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();

        int color;
        if (bannerHit) {
            color = COLOR_BANNER;
        } else if (targetPointHit) {
            color = COLOR_TARGET;
        } else if (explored) {
            color = COLOR_EXPLORED;
        } else {
            color = COLOR_UNEXPLORED;
        }
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >> 24) & 0xFF;

        vc.addVertex(matrix4f, x - halfSize, y + halfSize, z).setColor(r, g, b, a).setUv(u0, v1).setLight(packedLight);
        vc.addVertex(matrix4f, x + halfSize, y + halfSize, z).setColor(r, g, b, a).setUv(u1, v1).setLight(packedLight);
        vc.addVertex(matrix4f, x + halfSize, y - halfSize, z).setColor(r, g, b, a).setUv(u1, v0).setLight(packedLight);
        vc.addVertex(matrix4f, x - halfSize, y - halfSize, z).setColor(r, g, b, a).setUv(u0, v0).setLight(packedLight);
    }
}