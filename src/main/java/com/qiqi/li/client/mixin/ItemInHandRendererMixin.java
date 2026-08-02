package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.LivingMapClientCache;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    private static final int MAP_SIZE = 128;
    private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");

    @Inject(method = "renderMap", at = @At("TAIL"))
    private void renderLivingMapTargetMarker(PoseStack poseStack, MultiBufferSource buffer, int packedLight, ItemStack stack, CallbackInfo ci) {
        if (!isLivingMap(stack)) return;
        if (this.minecraft.player == null || this.minecraft.level == null) return;

        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, this.minecraft.level);
        if (mapData == null) return;

        LivingMapClientCache.MapMetadata metadata = LivingMapClientCache.get(mapId.id());
        if (metadata == null) return;

        int[] target = calcTargetMapPixel(mapData, metadata, this.minecraft.player);
        if (target == null) return;

        int targetMapX = target[0];
        int targetMapY = target[1];

        if (targetMapX < 0 || targetMapX >= MAP_SIZE || targetMapY < 0 || targetMapY >= MAP_SIZE) return;

        boolean bannerHit = MapCoordHelper.isBannerDecorationHit(mapData, targetMapX, targetMapY);
        MapDecoration targetPoint = !bannerHit ? MapCoordHelper.findTargetPointHit(mapData, targetMapX, targetMapY) : null;
        boolean explored = MapCoordHelper.isExplored(mapData, targetMapX, targetMapY);

        renderMarker(buffer, poseStack, targetMapX, targetMapY, explored, bannerHit, targetPoint != null, packedLight);
    }

    private void renderMarker(MultiBufferSource buffer, PoseStack poseStack, int mapX, int mapY, boolean explored, boolean bannerHit, boolean targetPointHit, int packedLight) {
        TextureAtlasSprite sprite = this.minecraft.getGuiSprites().getSprite(CROSSHAIR_SPRITE);
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
            color = 0xFFFFAA00;
        } else if (targetPointHit) {
            color = 0xFF00DDFF;
        } else if (explored) {
            color = 0xFF00FF00;
        } else {
            color = 0xFFFF3333;
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

    private int[] calcTargetMapPixel(MapItemSavedData mapData, LivingMapClientCache.MapMetadata metadata, Player player) {
        int scale = 1 << mapData.scale;
        int centerX = metadata.centerX();
        int centerZ = metadata.centerZ();
        boolean sameDimension = player.level().dimension() == metadata.dimension();

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        double originX, originZ;
        if (sameDimension) {
            int playerMapX = (int) ((player.getX() - centerX) / scale) + 64;
            int playerMapY = (int) ((player.getZ() - centerZ) / scale) + 64;
            boolean onMap = playerMapX >= 0 && playerMapX < MAP_SIZE && playerMapY >= 0 && playerMapY < MAP_SIZE;

            if (onMap) {
                originX = player.getX();
                originZ = player.getZ();
            } else {
                originX = centerX;
                originZ = centerZ;
            }
        } else {
            originX = centerX;
            originZ = centerZ;
        }

        double maxDist = MapCoordHelper.calcMaxDistToMapEdge(originX, originZ, dx, dz, centerX, centerZ, scale);
        double distance = ((90.0 - pitch) / 90.0) * maxDist;
        distance = Math.max(0, Math.min(distance, maxDist));

        double targetWorldX = originX + dx * distance;
        double targetWorldZ = originZ + dz * distance;

        int targetMapX = (int) ((targetWorldX - centerX) / scale) + 64;
        int targetMapY = (int) ((targetWorldZ - centerZ) / scale) + 64;

        return new int[]{targetMapX, targetMapY};
    }

    private boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack);
    }
}