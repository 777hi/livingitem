package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.render.LivingMapTargetRenderer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.LivingMapClientCache;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
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

    @Inject(method = "renderMap", at = @At("TAIL"))
    private void renderLivingMapTargetMarker(PoseStack poseStack, MultiBufferSource buffer, int packedLight, ItemStack stack, CallbackInfo ci) {
        if (!LivingItemManager.isLivingMap(stack)) return;
        if (this.minecraft.player == null || this.minecraft.level == null) return;

        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, this.minecraft.level);
        if (mapData == null) return;

        LivingMapClientCache.MapMetadata metadata = LivingMapClientCache.get(mapId.id());
        if (metadata == null) return;

        int[] target = MapCoordHelper.calcClientTarget(mapData, metadata, this.minecraft.player);
        if (target == null) return;

        int targetMapX = target[0];
        int targetMapY = target[1];

        if (targetMapX < 0 || targetMapX >= MapCoordHelper.MAP_SIZE || targetMapY < 0 || targetMapY >= MapCoordHelper.MAP_SIZE) return;

        boolean decoHit = MapCoordHelper.isTeleportableDecorationHit(mapData, targetMapX, targetMapY);
        boolean explored = MapCoordHelper.isExplored(mapData, targetMapX, targetMapY);

        LivingMapTargetRenderer.renderMarker(this.minecraft, buffer, poseStack, targetMapX, targetMapY, explored, decoHit, packedLight);
    }
}