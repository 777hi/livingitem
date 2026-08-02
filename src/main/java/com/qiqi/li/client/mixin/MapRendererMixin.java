package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.MapRenderer.class)
public class MapRendererMixin {

    private static final int MAP_SIZE = 128;
    private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");

    @Inject(method = "render", at = @At("TAIL"))
    private void renderItemFrameCursor(PoseStack poseStack, MultiBufferSource buffer, MapId mapId, MapItemSavedData mapData, boolean active, int packedLight, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!isHoldingLivingEnderPearl(mc.player)) return;

        if (!(mc.hitResult instanceof EntityHitResult hitResult)) return;
        if (!(hitResult.getEntity() instanceof ItemFrame frame)) return;
        if (!isLivingMap(frame.getItem())) return;

        MapId frameMapId = frame.getItem().get(DataComponents.MAP_ID);
        if (frameMapId == null || frameMapId.id() != mapId.id()) return;

        Vec3 hitVec = hitResult.getLocation();
        BlockPos framePos = frame.blockPosition();
        Direction facing = frame.getDirection();
        int rotation = frame.getRotation();

        MapCoordHelper.HitResult uv = MapCoordHelper.hitVecToMapPixel(hitVec, framePos, facing, rotation);
        int mapX = MapCoordHelper.uvToMapX(uv.u());
        int mapY = MapCoordHelper.uvToMapY(uv.v());

        if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) return;

        boolean bannerHit = MapCoordHelper.isBannerDecorationHit(mapData, mapX, mapY);
        MapDecoration targetPoint = !bannerHit ? MapCoordHelper.findTargetPointHit(mapData, mapX, mapY) : null;
        boolean explored = MapCoordHelper.isExplored(mapData, mapX, mapY);

        renderMarker(mc, buffer, poseStack, mapX, mapY, explored, bannerHit, targetPoint != null, packedLight);
    }

    private void renderMarker(Minecraft mc, MultiBufferSource buffer, PoseStack poseStack, int mapX, int mapY, boolean explored, boolean bannerHit, boolean targetPointHit, int packedLight) {
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

    private static boolean isHoldingLivingEnderPearl(net.minecraft.world.entity.player.Player player) {
        return LivingEnderPearlFunction.isLivingEnderPearl(player.getMainHandItem())
            || LivingEnderPearlFunction.isLivingEnderPearl(player.getOffhandItem());
    }

    private static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack);
    }
}