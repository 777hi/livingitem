package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
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

/**
 * 物品展示框地图传送处理器（服务端事件监听）。
 *
 * <p>监听「右键点击装着活地图的物品展示框」事件：取消原版旋转逻辑，改用玩家手持的活末影珍珠
 * 作支付，把玩家传送到地图上被点击像素对应的世界坐标（支持跨维度）。坐标换算委托
 * {@link MapCoordHelper}，实际传送委托 {@link MapTeleportExecutor}。</p>
 */
public final class ItemFrameMapTeleportHandler {

    private ItemFrameMapTeleportHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(ItemFrameMapTeleportHandler.class);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof ItemFrame frame)) return;
        if (!LivingItemManager.isLivingMap(frame.getItem())) return;

        ItemStack heldItem = event.getEntity().getMainHandItem();
        if (!LivingEnderPearlFunction.isLivingEnderPearl(heldItem)) {
            heldItem = event.getEntity().getOffhandItem();
            if (!LivingEnderPearlFunction.isLivingEnderPearl(heldItem)) return;
        }

        // 客户端和服务端都取消事件，防止原版展示框旋转逻辑执行
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));

        // 传送逻辑仅在服务端执行
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

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