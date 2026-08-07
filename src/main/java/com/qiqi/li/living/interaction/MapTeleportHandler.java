package com.qiqi.li.living.interaction;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import com.qiqi.li.living.domain.map.TeleportHelper;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public class MapTeleportHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack mapStack = targetSlot.getItem();
        if (!LivingItemManager.isLivingMap(mapStack)) return;

        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        ServerLevel sourceLevel = player.serverLevel();
        MapItemSavedData mapData = MapItem.getSavedData(mapId, sourceLevel);
        if (mapData == null) return;

        ServerLevel targetLevel = sourceLevel.getServer().getLevel(MapCoordHelper.getMapDimension(mapData));
        if (targetLevel == null) return;

        ItemStack pearlStack;
        if (player.isCreative()) {
            pearlStack = LivingEnderPearlFunction.findInInventory(player);
            if (pearlStack == null) return;
        } else {
            ItemStack carried = player.containerMenu.getCarried();
            if (carried.isEmpty() || !LivingEnderPearlFunction.isLivingEnderPearl(carried)) return;
            if (LivingEnderPearlFunction.isOnCooldown(player)) return;

            player.containerMenu.setCarried(ItemStack.EMPTY);
            safeReturnCarried(player, carried);

            pearlStack = LivingEnderPearlFunction.findInInventory(player);
            if (pearlStack == null) return;
        }

        boolean success = TeleportHelper.teleportToMapPosition(
            player, sourceLevel, targetLevel,
            mapData.centerX, mapData.centerZ, pearlStack);

        if (success) {
            if (player.level().dimension() != targetLevel.dimension()) {
                TeleportHelper.sendCrossDimensionMessage(player, targetLevel.dimension());
            }
            TeleportHelper.sendMapCenterMessage(player);
        }

        player.containerMenu.broadcastChanges();
    }

    private static void safeReturnCarried(ServerPlayer player, ItemStack carried) {
        player.getInventory().add(carried);
        if (!carried.isEmpty()) {
            player.drop(carried, false);
        }
    }
}