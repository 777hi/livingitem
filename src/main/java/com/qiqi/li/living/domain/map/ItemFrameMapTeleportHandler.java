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
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ItemFrameMapTeleportHandler {

    private ItemFrameMapTeleportHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(ItemFrameMapTeleportHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof ItemFrame frame)) return;
        if (!LivingItemManager.isLivingMap(frame.getItem())) return;

        ItemStack heldItem = player.getMainHandItem();
        if (!LivingEnderPearlFunction.isLivingEnderPearl(heldItem)) {
            heldItem = player.getOffhandItem();
            if (!LivingEnderPearlFunction.isLivingEnderPearl(heldItem)) return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide()));

        if (LivingEnderPearlFunction.isOnCooldown(player)) return;

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

        if (mapU < 0 || mapU > 1 || mapV < 0 || mapV > 1) {
            return;
        }

        int mapX = MapCoordHelper.uvToMapX(mapU);
        int mapY = MapCoordHelper.uvToMapY(mapV);

        if (mapX < 0 || mapX >= MapCoordHelper.MAP_SIZE || mapY < 0 || mapY >= MapCoordHelper.MAP_SIZE) {
            return;
        }

        ItemStack pearlStack = MapTeleportExecutor.resolvePearlStack(player, heldItem);
        if (pearlStack == null) return;

        double[] preciseWorldPos = MapCoordHelper.uvToWorldPos(mapData, mapU, mapV);

        MapTeleportExecutor.execute(
            player, sourceLevel, targetLevel, mapData,
            mapX, mapY,
            preciseWorldPos[0], preciseWorldPos[1],
            frame.getItem(), pearlStack);
    }
}