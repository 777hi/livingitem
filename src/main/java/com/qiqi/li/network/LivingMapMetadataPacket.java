package com.qiqi.li.network;

import com.qiqi.li.living.domain.map.LivingMapClientCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LivingMapMetadataPacket(
    int mapId,
    int centerX,
    int centerZ,
    String dimensionKey
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "living_map_metadata");
    public static final CustomPacketPayload.Type<LivingMapMetadataPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, LivingMapMetadataPacket> STREAM_CODEC = StreamCodec.of(
        LivingMapMetadataPacket::encode,
        LivingMapMetadataPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, LivingMapMetadataPacket pkt) {
        buf.writeVarInt(pkt.mapId);
        buf.writeVarInt(pkt.centerX);
        buf.writeVarInt(pkt.centerZ);
        buf.writeUtf(pkt.dimensionKey);
    }

    private static LivingMapMetadataPacket decode(FriendlyByteBuf buf) {
        int mapId = buf.readVarInt();
        int centerX = buf.readVarInt();
        int centerZ = buf.readVarInt();
        String dimensionKey = buf.readUtf();
        return new LivingMapMetadataPacket(mapId, centerX, centerZ, dimensionKey);
    }

    @Override
    public Type<LivingMapMetadataPacket> type() {
        return TYPE;
    }

    public static void handle(LivingMapMetadataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            LivingMapClientCache.update(packet.mapId, packet.centerX, packet.centerZ, packet.dimensionKey);
        });
    }
}