package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;

public final class MapTeleportExecutor {

    public static final int UNEXPLORED_PEARL_COST = 16;

    private MapTeleportExecutor() {}

    public static Result execute(ServerPlayer player, ServerLevel sourceLevel, ServerLevel targetLevel,
                                 MapItemSavedData mapData, int mapX, int mapY,
                                 double preciseWorldX, double preciseWorldZ,
                                 @Nullable ItemStack mapStack, ItemStack pearlStack) {
        MapBanner banner = MapCoordHelper.findBannerHit(mapData, mapX, mapY);
        MapDecoration targetPoint = banner == null ? MapCoordHelper.findTargetPointHit(mapData, mapX, mapY) : null;
        boolean success;

        if (banner != null) {
            success = TeleportHelper.teleportToBanner(player, sourceLevel, targetLevel, banner.pos(), pearlStack, false);
            if (success) {
                banner.name().ifPresent(name -> TeleportHelper.sendBannerTeleportMessage(player, name));
            }
        } else if (targetPoint != null) {
            double[] worldPos = resolveTargetPointWorldPos(mapStack, mapData, targetPoint);
            success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, worldPos[0], worldPos[1], pearlStack, false);
            if (success) {
                TeleportHelper.sendTargetPointMessage(player);
            }
        } else {
            boolean unexplored = !MapCoordHelper.isExplored(mapData, mapX, mapY);
            if (unexplored) {
                // 创造模式免费（consumePearl 内按 isCreative 短路），生存模式需背包中有足量珍珠。
                // 短路顺序保证创造模式不做多余的背包遍历
                if (player.isCreative()
                        || LivingEnderPearlFunction.countInInventory(player) >= UNEXPLORED_PEARL_COST) {
                    success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, preciseWorldX, preciseWorldZ, pearlStack, true);
                } else {
                    TeleportHelper.sendUnexploredMessage(player);
                    return new Result(false, false);
                }
            } else {
                success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, preciseWorldX, preciseWorldZ, pearlStack, false);
            }
        }

        boolean crossDim = false;
        if (success && player.level().dimension() != targetLevel.dimension()) {
            crossDim = true;
            TeleportHelper.sendCrossDimensionMessage(player, targetLevel.dimension());
        }

        return new Result(success, crossDim);
    }

    private static double[] resolveTargetPointWorldPos(@Nullable ItemStack mapStack, MapItemSavedData mapData, MapDecoration targetPoint) {
        return MapCoordHelper.getTargetPointWorldPos(mapStack, mapData, targetPoint);
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