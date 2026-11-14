package com.qiqi.li.living.domain.portal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 门面与接收者菜单槽映射；不接受其他菜单的在途快照。 */
public record PortalSnapshotPacket(int menuId, String containerKey, int width, int size,
                                   Set<Integer> cells, Map<Integer, Integer> menuSlots)
        implements CustomPacketPayload {
    public static final Type<PortalSnapshotPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("living_item", "portal_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, PortalSnapshotPacket> STREAM_CODEC =
        StreamCodec.of(PortalSnapshotPacket::encode, PortalSnapshotPacket::decode);
    private static final int MAX_SLOTS = 65536;

    public PortalSnapshotPacket {
        cells = Set.copyOf(cells);
        menuSlots = Map.copyOf(menuSlots);
    }

    @Override
    public Type<PortalSnapshotPacket> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, PortalSnapshotPacket packet) {
        buf.writeVarInt(packet.menuId());
        buf.writeUtf(packet.containerKey());
        buf.writeVarInt(packet.width());
        buf.writeVarInt(packet.size());
        buf.writeVarInt(packet.cells().size());
        for (int cell : packet.cells()) buf.writeVarInt(cell);
        buf.writeVarInt(packet.menuSlots().size());
        packet.menuSlots().forEach((menu, logical) -> {
            buf.writeVarInt(menu);
            buf.writeVarInt(logical);
        });
    }

    private static int readCount(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_SLOTS) throw new IllegalArgumentException("Invalid portal slot count");
        return count;
    }

    private static PortalSnapshotPacket decode(FriendlyByteBuf buf) {
        int menuId = buf.readVarInt();
        String key = buf.readUtf();
        int width = readCount(buf);
        int size = readCount(buf);
        Set<Integer> cells = new HashSet<>();
        int count = readCount(buf);
        for (int i = 0; i < count; i++) {
            int slot = buf.readVarInt();
            if (slot < 0 || slot >= size) throw new IllegalArgumentException("Invalid portal cell");
            cells.add(slot);
        }
        Map<Integer, Integer> slots = new HashMap<>();
        count = readCount(buf);
        for (int i = 0; i < count; i++) {
            int menu = buf.readVarInt();
            int logical = buf.readVarInt();
            if (menu < 0 || logical < 0 || logical >= size) {
                throw new IllegalArgumentException("Invalid portal menu mapping");
            }
            slots.put(menu, logical);
        }
        return new PortalSnapshotPacket(menuId, key, width, size, cells, slots);
    }

    public static void handle(PortalSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player != null) PortalClientCache.update(player.containerMenu.containerId, packet);
        });
    }
}
