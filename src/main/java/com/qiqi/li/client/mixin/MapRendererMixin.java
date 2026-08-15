package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.render.LivingMapTargetRenderer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.MapRenderer.class)
public class MapRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void renderItemFrameCursor(PoseStack poseStack, MultiBufferSource buffer, MapId mapId, MapItemSavedData mapData, boolean active, int packedLight, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!isHoldingLivingEnderPearl(mc.player)) return;

        if (!(mc.hitResult instanceof EntityHitResult hitResult)) return;
        if (!(hitResult.getEntity() instanceof ItemFrame frame)) return;
        if (!LivingItemManager.isLivingMap(frame.getItem())) return;

        MapId frameMapId = frame.getItem().get(DataComponents.MAP_ID);
        if (frameMapId == null || frameMapId.id() != mapId.id()) return;

        Vec3 hitVec = hitResult.getLocation();
        BlockPos framePos = frame.blockPosition();
        Direction facing = frame.getDirection();
        int rotation = frame.getRotation();

        MapCoordHelper.HitResult uv = MapCoordHelper.hitVecToMapPixel(hitVec, framePos, facing, rotation);
        int mapX = MapCoordHelper.uvToMapX(uv.u());
        int mapY = MapCoordHelper.uvToMapY(uv.v());

        if (mapX < 0 || mapX >= MapCoordHelper.MAP_SIZE || mapY < 0 || mapY >= MapCoordHelper.MAP_SIZE) return;

        boolean decoHit = MapCoordHelper.isTeleportableDecorationHit(mapData, mapX, mapY);
        boolean explored = MapCoordHelper.isExplored(mapData, mapX, mapY);

        LivingMapTargetRenderer.renderMarker(mc, buffer, poseStack, mapX, mapY, explored, decoHit, packedLight);
    }

    private static boolean isHoldingLivingEnderPearl(net.minecraft.world.entity.player.Player player) {
        return LivingEnderPearlFunction.isLivingEnderPearl(player.getMainHandItem())
            || LivingEnderPearlFunction.isLivingEnderPearl(player.getOffhandItem());
    }
}