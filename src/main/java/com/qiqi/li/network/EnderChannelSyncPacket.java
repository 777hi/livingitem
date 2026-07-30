package com.qiqi.li.network;

import com.qiqi.li.living.domain.ender.EnderChannelClientCache;
import com.qiqi.li.living.domain.ender.EnderChannelEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record EnderChannelSyncPacket(
    int channel,
    int channelSize,
    int totalRoutes,
    List<EnderChannelClientCache.EntryDisplay> entries
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "ender_channel_sync");
    public static final CustomPacketPayload.Type<EnderChannelSyncPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, EnderChannelSyncPacket> STREAM_CODEC = StreamCodec.of(
        EnderChannelSyncPacket::encode,
        EnderChannelSyncPacket::decode
    );

    public static EnderChannelSyncPacket fromRegistry(int channel, int channelSize, int totalRoutes,
                                                       List<EnderChannelEntry> rawEntries) {
        List<EnderChannelClientCache.EntryDisplay> displays = new ArrayList<>(rawEntries.size());
        for (EnderChannelEntry e : rawEntries) {
            displays.add(new EnderChannelClientCache.EntryDisplay(
                e.itemType(),
                e.sourceDim() != null ? e.sourceDim().location().toString() : null,
                e.sourcePos(),
                e.sourceSlot()
            ));
        }
        return new EnderChannelSyncPacket(channel, channelSize, totalRoutes, displays);
    }

    private static void encode(FriendlyByteBuf buf, EnderChannelSyncPacket pkt) {
        buf.writeVarInt(pkt.channel);
        buf.writeVarInt(pkt.channelSize);
        buf.writeVarInt(pkt.totalRoutes);
        buf.writeVarInt(pkt.entries.size());
        for (EnderChannelClientCache.EntryDisplay e : pkt.entries) {
            buf.writeUtf(e.itemType());
            if (e.dimKey() != null) {
                buf.writeBoolean(true);
                buf.writeUtf(e.dimKey());
            } else {
                buf.writeBoolean(false);
            }
            if (e.sourcePos() != null) {
                buf.writeBoolean(true);
                buf.writeBlockPos(e.sourcePos());
            } else {
                buf.writeBoolean(false);
            }
            buf.writeVarInt(e.sourceSlot());
        }
    }

    private static EnderChannelSyncPacket decode(FriendlyByteBuf buf) {
        int channel = buf.readVarInt();
        int channelSize = buf.readVarInt();
        int totalRoutes = buf.readVarInt();
        int entryCount = buf.readVarInt();
        List<EnderChannelClientCache.EntryDisplay> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            String itemType = buf.readUtf();
            String dimKey = buf.readBoolean() ? buf.readUtf() : null;
            BlockPos sourcePos = buf.readBoolean() ? buf.readBlockPos() : null;
            int sourceSlot = buf.readVarInt();
            entries.add(new EnderChannelClientCache.EntryDisplay(itemType, dimKey, sourcePos, sourceSlot));
        }
        return new EnderChannelSyncPacket(channel, channelSize, totalRoutes, entries);
    }

    @Override
    public Type<EnderChannelSyncPacket> type() {
        return TYPE;
    }

    public static void handle(EnderChannelSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            EnderChannelClientCache.update(
                packet.channel,
                packet.channelSize,
                packet.totalRoutes,
                packet.entries
            );
        });
    }
}