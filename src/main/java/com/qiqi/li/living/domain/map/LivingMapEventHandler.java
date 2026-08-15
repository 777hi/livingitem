package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EmptyMapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.qiqi.li.network.LivingMapMetadataPacket;

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

        StructureMapDecorator.scanStructuresLazy(player.serverLevel(), player, mapStack, mapData);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel sourceLevel)) return;

        if (handleLivingMapCreation(event, player, sourceLevel)) return;

        ItemStack mapStack = event.getItemStack();
        if (!LivingItemManager.isLivingMap(mapStack)) return;

        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, sourceLevel);
        if (mapData == null) return;

        ItemStack pearlStack = LivingEnderPearlFunction.findInInventory(player);
        if (pearlStack == null) return;

        ServerLevel targetLevel = sourceLevel.getServer().getLevel(MapCoordHelper.getMapDimension(mapData));
        if (targetLevel == null) return;

        MapCoordHelper.TargetResult target = MapCoordHelper.getTargetFromYawPitch(mapData, player);

        if (target.mapX() < 0 || target.mapX() >= MapCoordHelper.MAP_SIZE
            || target.mapY() < 0 || target.mapY() >= MapCoordHelper.MAP_SIZE) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(sourceLevel.isClientSide()));
            return;
        }

        MapTeleportExecutor.Result result = MapTeleportExecutor.execute(
            player, sourceLevel, targetLevel, mapData,
            target.mapX(), target.mapY(),
            target.worldX(), target.worldZ(),
            mapStack, pearlStack);

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(sourceLevel.isClientSide()));
    }

    @Nullable
    private static ItemStack getHeldLivingMap(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (LivingItemManager.isLivingMap(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (LivingItemManager.isLivingMap(offHand)) return offHand;
        return null;
    }

    private static boolean handleLivingMapCreation(PlayerInteractEvent.RightClickItem event,
                                                    ServerPlayer player, ServerLevel level) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof EmptyMapItem)) return false;
        if (!LivingItemManager.isLivingItem(stack)) return false;

        InteractionHand hand = event.getHand();
        int count = stack.getCount();
        double distance = 128.0 * count * count;

        float yaw = player.getYRot();
        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        double targetX = player.getX() + dx * distance;
        double targetZ = player.getZ() + dz * distance;

        int centerX = MapCoordHelper.calculateMapCenterCoord(targetX, 0);
        int centerZ = MapCoordHelper.calculateMapCenterCoord(targetZ, 0);

        stack.consume(1, player);
        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
        player.level().playSound(null, player, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, player.getSoundSource(), 1.0F, 1.0F);

        ItemStack newMap = MapItem.create(level, centerX, centerZ, (byte)0, true, false);
        LivingItemManager.setLiving(newMap, true);
        MapItem.renderBiomePreviewMap(level, newMap);

        if (stack.isEmpty()) {
            player.setItemInHand(hand, newMap);
        } else {
            if (!player.getInventory().add(newMap.copy())) {
                player.drop(newMap, false);
            }
        }

        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
        event.setCanceled(true);
        return true;
    }
}