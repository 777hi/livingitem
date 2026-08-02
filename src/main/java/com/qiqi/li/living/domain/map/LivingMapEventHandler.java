package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import com.qiqi.li.network.LivingMapMetadataPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

public final class LivingMapEventHandler {

    private LivingMapEventHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(LivingMapEventHandler.class);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return;

        ItemStack mapStack = getHeldLivingMap(player);
        if (mapStack == null) return;

        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, player.serverLevel());
        if (mapData == null) return;

        PacketDistributor.sendToPlayer(player, new LivingMapMetadataPacket(
            mapId.id(),
            mapData.centerX,
            mapData.centerZ,
            mapData.dimension.location().toString()
        ));
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) return;
        if (!(serverPlayer.level() instanceof ServerLevel sourceLevel)) return;

        ItemStack mapStack = event.getItemStack();
        if (!isLivingMap(mapStack)) return;

        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, sourceLevel);
        if (mapData == null) return;

        ItemStack pearlStack = findPearlInInventory(serverPlayer);
        if (pearlStack == null) return;

        ServerLevel targetLevel = sourceLevel.getServer().getLevel(MapCoordHelper.getMapDimension(mapData));
        if (targetLevel == null) return;

        MapCoordHelper.TargetResult target = MapCoordHelper.getTargetFromYawPitch(mapData, serverPlayer);

        if (target.mapX() < 0 || target.mapX() >= 128 || target.mapY() < 0 || target.mapY() >= 128) return;

        MapBanner banner = MapCoordHelper.findBannerHit(mapData, target.mapX(), target.mapY());
        MapDecoration targetPoint = banner == null ? MapCoordHelper.findTargetPointHit(mapData, target.mapX(), target.mapY()) : null;
        boolean success;

        if (banner != null) {
            success = TeleportHelper.teleportToBanner(serverPlayer, sourceLevel, targetLevel, banner.pos(), pearlStack);
            if (success) {
                banner.name().ifPresent(name ->
                    TeleportHelper.sendBannerTeleportMessage(serverPlayer, name));
            }
        } else if (targetPoint != null) {
            double[] worldPos = MapCoordHelper.getTargetPointWorldPos(mapStack, mapData, targetPoint);
            success = TeleportHelper.teleportToMapPosition(
                serverPlayer, sourceLevel, targetLevel,
                worldPos[0], worldPos[1], pearlStack);
            if (success) {
                TeleportHelper.sendTargetPointMessage(serverPlayer);
            }
        } else {
            if (!MapCoordHelper.isExplored(mapData, target.mapX(), target.mapY())) {
                TeleportHelper.sendUnexploredMessage(serverPlayer);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.sidedSuccess(sourceLevel.isClientSide()));
                return;
            }

            success = TeleportHelper.teleportToMapPosition(
                serverPlayer, sourceLevel, targetLevel,
                target.worldX(), target.worldZ(), pearlStack);
        }

        if (success) {
            if (serverPlayer.level().dimension() != targetLevel.dimension()) {
                TeleportHelper.sendCrossDimensionMessage(serverPlayer, targetLevel.dimension());
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(sourceLevel.isClientSide()));
        }
    }

    @Nullable
    private static ItemStack findPearlInInventory(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (LivingEnderPearlFunction.isLivingEnderPearl(stack) && !LivingEnderPearlFunction.isOnCooldown(stack)) {
                return stack;
            }
        }
        return null;
    }

    private static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack);
    }

    @Nullable
    private static ItemStack getHeldLivingMap(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (isLivingMap(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (isLivingMap(offHand)) return offHand;
        return null;
    }
}