package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;

public final class ItemFrameMapTeleportHandler {

    private static final int MAP_SIZE = 128;

    private ItemFrameMapTeleportHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(ItemFrameMapTeleportHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof ItemFrame frame)) return;
        if (!isLivingMap(frame.getItem())) return;

        ItemStack heldItem = player.getMainHandItem();
        boolean offHand = false;
        if (!isLivingEnderPearl(heldItem)) {
            heldItem = player.getOffhandItem();
            offHand = true;
            if (!isLivingEnderPearl(heldItem)) return;
        }
        if (LivingEnderPearlFunction.isOnCooldown(heldItem)) return;

        MapId mapId = frame.getItem().get(DataComponents.MAP_ID);
        if (mapId == null) return;

        ServerLevel sourceLevel = player.serverLevel();
        MapItemSavedData mapData = MapItem.getSavedData(mapId, sourceLevel);
        if (mapData == null) return;

        ServerLevel targetLevel = sourceLevel.getServer().getLevel(MapCoordHelper.getMapDimension(mapData));
        if (targetLevel == null) return;

        Vec3 localPos = event.getLocalPos();
        Vec3 worldHit = new Vec3(
            frame.getX() + localPos.x,
            frame.getY() + localPos.y,
            frame.getZ() + localPos.z
        );

        BlockPos framePos = frame.blockPosition();
        Direction facing = frame.getDirection();
        int rotation = frame.getRotation();

        MapCoordHelper.HitResult uv = MapCoordHelper.hitVecToMapPixel(worldHit, framePos, facing, rotation);

        double mapU = uv.u();
        double mapV = uv.v();

        if (mapU < 0 || mapU > 1 || mapV < 0 || mapV > 1) return;

        int mapX = MapCoordHelper.uvToMapX(mapU);
        int mapY = MapCoordHelper.uvToMapY(mapV);

        if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) return;

        ItemStack pearlStack = player.isCreative()
            ? findPearlInInventory(player)
            : heldItem;
        if (pearlStack == null) return;

        MapBanner banner = MapCoordHelper.findBannerHit(mapData, mapX, mapY);
        MapDecoration targetPoint = banner == null ? MapCoordHelper.findTargetPointHit(mapData, mapX, mapY) : null;
        boolean success;

        if (banner != null) {
            success = TeleportHelper.teleportToBanner(player, sourceLevel, targetLevel, banner.pos(), pearlStack);
            if (success) {
                banner.name().ifPresent(name -> TeleportHelper.sendBannerTeleportMessage(player, name));
            }
        } else if (targetPoint != null) {
            double[] worldPos = MapCoordHelper.getTargetPointWorldPos(frame.getItem(), mapData, targetPoint);
            success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, worldPos[0], worldPos[1], pearlStack);
            if (success) {
                TeleportHelper.sendTargetPointMessage(player);
            }
        } else {
            if (!MapCoordHelper.isExplored(mapData, mapX, mapY)) {
                TeleportHelper.sendUnexploredMessage(player);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.sidedSuccess(sourceLevel.isClientSide()));
                return;
            }
            BlockPos worldPos = MapCoordHelper.mapPixelToWorld(mapData, mapX, mapY);
            success = TeleportHelper.teleportToMapPosition(player, sourceLevel, targetLevel, worldPos.getX(), worldPos.getZ(), pearlStack);
        }

        if (success) {
            if (player.level().dimension() != targetLevel.dimension()) {
                TeleportHelper.sendCrossDimensionMessage(player, targetLevel.dimension());
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(sourceLevel.isClientSide()));
        }
    }

    private static boolean isLivingEnderPearl(ItemStack stack) {
        return stack.is(Items.ENDER_PEARL) && LivingItemManager.isLivingItem(stack);
    }

    private static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack);
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
}