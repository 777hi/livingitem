package com.qiqi.li.network;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import com.qiqi.li.living.domain.map.MapTeleportExecutor;
import com.qiqi.li.living.domain.map.TeleportHelper;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;

public record LivingMapGuiTeleportPacket(
    int topLeftSlotIndex,
    float u,
    float v,
    @Nullable CompoundTag carriedTag
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
        if (pkt.carriedTag != null) {
            buf.writeBoolean(true);
            buf.writeNbt(pkt.carriedTag);
        } else {
            buf.writeBoolean(false);
        }
    }

    private static LivingMapGuiTeleportPacket decode(FriendlyByteBuf buf) {
        int topLeftSlotIndex = buf.readVarInt();
        float u = buf.readFloat();
        float v = buf.readFloat();
        CompoundTag carriedTag = null;
        if (buf.readBoolean()) {
            carriedTag = buf.readNbt();
        }
        return new LivingMapGuiTeleportPacket(topLeftSlotIndex, u, v, carriedTag);
    }

    @Override
    public Type<LivingMapGuiTeleportPacket> type() {
        return TYPE;
    }

    public static void handle(LivingMapGuiTeleportPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            AbstractContainerMenu menu = player.containerMenu;

            boolean carriedRestored = false;
            if (player.isCreative() && packet.carriedTag() != null) {
                ItemStack carried = ItemStack.parse(player.registryAccess(), packet.carriedTag())
                    .orElse(ItemStack.EMPTY);
                if (!carried.isEmpty()) {
                    menu.setCarried(carried);
                    carriedRestored = true;
                }
            }

            Slot topLeftSlot = resolveSlot(menu, packet.topLeftSlotIndex());
            if (topLeftSlot == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            ItemStack mapStack = topLeftSlot.getItem();
            if (!LivingItemManager.isLivingMap(mapStack)) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            MapId mapId = mapStack.get(DataComponents.MAP_ID);
            if (mapId == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            ServerLevel sourceLevel = player.serverLevel();
            MapItemSavedData mapData = MapItem.getSavedData(mapId, sourceLevel);
            if (mapData == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            ServerLevel targetLevel = sourceLevel.getServer().getLevel(MapCoordHelper.getMapDimension(mapData));
            if (targetLevel == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            float u = Math.max(0f, Math.min(1f, packet.u()));
            float v = Math.max(0f, Math.min(1f, packet.v()));

            ItemStack pearlStack = resolvePearlStack(player, menu);
            if (pearlStack == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            int mapX = MapCoordHelper.uvToMapX(u);
            int mapY = MapCoordHelper.uvToMapY(v);

            double[] preciseWorldPos = MapCoordHelper.uvToWorldPos(mapData, u, v);

            MapTeleportExecutor.Result result = MapTeleportExecutor.execute(
                player, sourceLevel, targetLevel, mapData,
                mapX, mapY,
                preciseWorldPos[0], preciseWorldPos[1],
                mapStack, pearlStack);

            if (result.success() && result.crossDim()) {
                TeleportHelper.sendCrossDimensionMessage(player, targetLevel.dimension());
            }

            if (carriedRestored) {
                ItemStack modifiedCarried = menu.getCarried().copy();
                menu.setCarried(ItemStack.EMPTY);
                CompoundTag carriedSyncTag = (CompoundTag) modifiedCarried.saveOptional(player.registryAccess());
                PacketDistributor.sendToPlayer(player, new CarriedUpdatePacket(carriedSyncTag));
            }

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

    private static ItemStack resolvePearlStack(ServerPlayer player, AbstractContainerMenu menu) {
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