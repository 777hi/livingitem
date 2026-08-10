package com.qiqi.li.network;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import com.qiqi.li.living.domain.map.MapTeleportExecutor;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import com.qiqi.li.logging.ModLog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LivingMapGuiTeleportPacket(
    int topLeftSlotIndex,
    float u,
    float v
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "map_gui_teleport");
    public static final CustomPacketPayload.Type<LivingMapGuiTeleportPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, LivingMapGuiTeleportPacket> STREAM_CODEC = StreamCodec.of(
        LivingMapGuiTeleportPacket::encode,
        LivingMapGuiTeleportPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, LivingMapGuiTeleportPacket pkt) {
        buf.writeVarInt(pkt.topLeftSlotIndex);
        buf.writeFloat(pkt.u);
        buf.writeFloat(pkt.v);
    }

    private static LivingMapGuiTeleportPacket decode(FriendlyByteBuf buf) {
        int topLeftSlotIndex = buf.readVarInt();
        float u = buf.readFloat();
        float v = buf.readFloat();
        return new LivingMapGuiTeleportPacket(topLeftSlotIndex, u, v);
    }

    @Override
    public Type<LivingMapGuiTeleportPacket> type() {
        return TYPE;
    }

    public static void handle(LivingMapGuiTeleportPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            AbstractContainerMenu menu = player.containerMenu;

            Slot topLeftSlot = resolveSlot(menu, packet.topLeftSlotIndex());
            if (topLeftSlot == null) {
                ModLog.TELEPORT.warn("GUI teleport: resolveSlot returned null for slotIndex={}", packet.topLeftSlotIndex());
                return;
            }

            ItemStack mapStack = topLeftSlot.getItem();
            if (!LivingItemManager.isLivingMap(mapStack)) {
                ModLog.TELEPORT.warn("GUI teleport: slot item is not a living map, item={}", mapStack);
                return;
            }

            MapId mapId = mapStack.get(DataComponents.MAP_ID);
            if (mapId == null) return;

            ServerLevel sourceLevel = player.serverLevel();
            MapItemSavedData mapData = MapItem.getSavedData(mapId, sourceLevel);
            if (mapData == null) return;

            ServerLevel targetLevel = sourceLevel.getServer().getLevel(MapCoordHelper.getMapDimension(mapData));
            if (targetLevel == null) return;

            float u = Math.max(0f, Math.min(1f, packet.u()));
            float v = Math.max(0f, Math.min(1f, packet.v()));

            ItemStack pearlStack = resolvePearlStack(player);
            if (pearlStack == null) {
                ModLog.TELEPORT.warn("GUI teleport: no living ender pearl found, player={}", player.getName().getString());
                return;
            }

            int mapX = MapCoordHelper.uvToMapX(u);
            int mapY = MapCoordHelper.uvToMapY(v);

            double[] preciseWorldPos = MapCoordHelper.uvToWorldPos(mapData, u, v);

            ModLog.TELEPORT.debug("GUI teleport: player={} slot={} uv=({},{}) map=({},{}) world=({},{})",
                player.getName().getString(), packet.topLeftSlotIndex(), u, v, mapX, mapY,
                preciseWorldPos[0], preciseWorldPos[1]);

            MapTeleportExecutor.Result result = MapTeleportExecutor.execute(
                player, sourceLevel, targetLevel, mapData,
                mapX, mapY,
                preciseWorldPos[0], preciseWorldPos[1],
                mapStack, pearlStack);

            ModLog.TELEPORT.debug("GUI teleport result: success={}", result.success());

            menu.broadcastChanges();
        });
    }

    private static Slot resolveSlot(AbstractContainerMenu menu, int slotListIndex) {
        if (slotListIndex >= 0 && slotListIndex < menu.slots.size()) {
            Slot slot = menu.slots.get(slotListIndex);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && LivingItemManager.isLivingItem(stack)) {
                return slot;
            }
        }
        return null;
    }

    private static ItemStack resolvePearlStack(ServerPlayer player) {
        if (LivingEnderPearlFunction.isOnCooldown(player)) return null;
        if (player.isCreative()) {
            return LivingEnderPearlFunction.findInInventory(player);
        }
        ItemStack mainHand = player.getMainHandItem();
        if (LivingEnderPearlFunction.isLivingEnderPearl(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (LivingEnderPearlFunction.isLivingEnderPearl(offHand)) return offHand;
        return null;
    }
}