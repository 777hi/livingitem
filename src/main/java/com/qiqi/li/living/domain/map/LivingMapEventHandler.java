package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
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
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.qiqi.li.network.LivingMapMetadataPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LivingMapEventHandler {

    /**
     * 已发送给各玩家的地图元数据快照，key 为玩家 UUID，value 为最近一次发送的包。
     * <p>MapItemSavedData 的 centerX/centerZ/dimension/scale 均为 final，同一 mapId 的
     * 元数据恒定不变，因此只在内容变化（切换地图、跨维度）时才需要发送。
     */
    private static final Map<UUID, LivingMapMetadataPacket> lastSentMetadata = new HashMap<>();

    private LivingMapEventHandler() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(LivingMapEventHandler.class);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastSentMetadata.clear();
        StructureMapDecorator.reset();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastSentMetadata.remove(event.getEntity().getUUID());
    }

    /**
     * 容器打开时，将容器内所有已展开的活地图的完整数据主动推送给客户端。
     *
     * <p>原版同步链（ServerPlayer.doTick → 遍历玩家背包 → getUpdatePacket）只覆盖
     * 玩家自己背包中的地图；容器里的地图永远不会注册 HoldingPlayer，退出存档重进后
     * 客户端 ClientLevel.mapData 为空，GUI 扩展地图渲染拿不到 MapItemSavedData 而空白。
     * 此处直接构造全量 ClientboundMapItemDataPacket（完整 128×128 colors + 装饰），
     * 客户端 handleMapItemData 会 createForClient + overrideMapData 建立数据。
     *
     * <p>服务端 MapItemSavedData 统一存储于 overworld 的 DataStorage（ServerLevel.getMapData），
     * 任意维度的玩家取数据均无问题。玩家背包中的地图由原版 doTick 同步，无需处理。
     */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        for (ItemStack stack : collectContainerLivingMaps(event.getContainer())) {
            MapId mapId = stack.get(DataComponents.MAP_ID);
            if (mapId == null) continue;

            MapItemSavedData mapData = MapItem.getSavedData(mapId, player.serverLevel());
            if (mapData == null) continue;

            player.connection.send(createFullMapPacket(mapId, mapData));
        }
    }

    /**
     * 收集容器菜单槽位中的全部已展开活地图（去重：同 mapId 只发一次）。
     */
    private static List<ItemStack> collectContainerLivingMaps(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        Map<Integer, ItemStack> unique = new HashMap<>();
        for (var slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (!LivingItemManager.isLivingMap(stack)) continue;

            MapId mapId = stack.get(DataComponents.MAP_ID);
            if (mapId == null) continue;
            unique.putIfAbsent(mapId.id(), stack);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 构造全量地图数据包：完整 128×128 颜色 + 全部装饰。
     */
    private static ClientboundMapItemDataPacket createFullMapPacket(MapId mapId, MapItemSavedData mapData) {
        byte[] colors = mapData.colors.clone();
        MapItemSavedData.MapPatch fullPatch = new MapItemSavedData.MapPatch(0, 0, 128, 128, colors);
        List<MapDecoration> decorations = new ArrayList<>();
        mapData.getDecorations().forEach(decorations::add);
        return new ClientboundMapItemDataPacket(
            mapId, mapData.scale, mapData.locked, decorations, fullPatch);
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

        LivingMapMetadataPacket packet = new LivingMapMetadataPacket(
            mapId.id(),
            mapData.centerX,
            mapData.centerZ,
            mapData.dimension.location().toString()
        );
        if (!packet.equals(lastSentMetadata.get(player.getUUID()))) {
            lastSentMetadata.put(player.getUUID(), packet);
            PacketDistributor.sendToPlayer(player, packet);
        }

        StructureMapDecorator.scanStructuresLazy(player.serverLevel(), player, mapStack, mapData);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack mapStack = event.getItemStack();

        boolean isLivingMap = LivingItemManager.isLivingMap(mapStack);
        boolean isLivingEmptyMap = (mapStack.getItem() instanceof EmptyMapItem) && LivingItemManager.isLivingItem(mapStack);

        if (!isLivingMap && !isLivingEmptyMap) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));

        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel sourceLevel)) return;

        if (isLivingEmptyMap) {
            handleLivingMapCreation(event, player, sourceLevel);
            return;
        }

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
            return;
        }

        // 直接执行传送；MapItemMixin 会在传送后跳过 MapItem.update() 的阻塞调用
        MapTeleportExecutor.execute(
            player, sourceLevel, targetLevel, mapData,
            target.mapX(), target.mapY(), target.worldX(), target.worldZ(),
            mapStack, pearlStack);
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

        return true;
    }
}