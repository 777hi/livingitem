package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;

public final class MapTeleportExecutor {

    private MapTeleportExecutor() {}

    public static Result execute(ServerPlayer player, ServerLevel sourceLevel, ServerLevel targetLevel,
                                 MapItemSavedData mapData, int mapX, int mapY,
                                 double preciseWorldX, double preciseWorldZ,
                                 @Nullable ItemStack mapStack, ItemStack pearlStack) {
        MapBanner banner = MapCoordHelper.findBannerHit(mapData, mapX, mapY);
        MapDecoration targetPoint = banner == null ? MapCoordHelper.findTargetPointHit(mapData, mapX, mapY) : null;
        boolean success;

        if (banner != null) {
            success = TeleportHelper.teleportToBanner(player, sourceLevel, targetLevel, banner.pos(), pearlStack);
            if (success) {
                banner.name().ifPresent(name -> TeleportHelper.sendBannerTeleportMessage(player, name));
            }
        } else if (targetPoint != null) {
            double[] worldPos = resolveTargetPointWorldPos(mapStack, mapData, targetPoint);
            success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, worldPos[0], worldPos[1], pearlStack);
            if (success) {
                TeleportHelper.sendTargetPointMessage(player);
            }
        } else {
            if (!MapCoordHelper.isExplored(mapData, mapX, mapY)) {
                TeleportHelper.sendUnexploredMessage(player);
                return new Result(false, false);
            }
            success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, preciseWorldX, preciseWorldZ, pearlStack);
        }

        boolean crossDim = false;
        if (success && player.level().dimension() != targetLevel.dimension()) {
            crossDim = true;
            TeleportHelper.sendCrossDimensionMessage(player, targetLevel.dimension());
        }

        return new Result(success, crossDim);
    }

    private static double[] resolveTargetPointWorldPos(@Nullable ItemStack mapStack, MapItemSavedData mapData, MapDecoration targetPoint) {
        if (mapStack != null) {
            double[] fromComponent = MapCoordHelper.getTargetPointWorldPos(mapStack, mapData, targetPoint);
            if (fromComponent != null) return fromComponent;
        }

        int scale = 1 << mapData.scale;
        float pixelX = (float) targetPoint.x() / 2.0F + 64.0F;
        float pixelY = (float) targetPoint.y() / 2.0F + 64.0F;
        return new double[]{
            mapData.centerX + (pixelX - 64.0F) * scale,
            mapData.centerZ + (pixelY - 64.0F) * scale
        };
    }

    public static ItemStack resolvePearlStack(ServerPlayer player, ItemStack heldItem) {
        if (LivingEnderPearlFunction.isOnCooldown(player)) return null;
        if (player.isCreative()) {
            return LivingEnderPearlFunction.findInInventory(player);
        }
        return LivingEnderPearlFunction.isLivingEnderPearl(heldItem) ? heldItem : null;
    }

    public record Result(boolean success, boolean crossDim) {}
}