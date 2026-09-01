package com.qiqi.li.network;

import com.qiqi.li.living.domain.ender.EnderChannelClientCache;
import com.qiqi.li.living.domain.ender.EnderChannelEntry;
import com.qiqi.li.living.domain.ender.EnderChannelKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C 路由快照同步包。
 *
 * <p>v12 起频道标识由 {@code int} 升级为 {@link EnderChannelKey}（归属玩家 + 堆叠数），
 * 以支持玩家专属频道。编码采用「布尔标志 + UUID + VarInt」，无状态、无句柄表，
 * 服务端与客户端各自独立计算频道键即可对齐。</p>
 *
 * <p>频道键是命名空间而非权限，专属频道路由不视为隐私，因此本包仍广播给所有在线玩家。</p>
 */
public record EnderChannelSyncPacket(
    EnderChannelKey channel,
    int channelSize,
    List<EnderChannelClientCache.EntryDisplay> entries
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "ender_channel_sync");
    public static final CustomPacketPayload.Type<EnderChannelSyncPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, EnderChannelSyncPacket> STREAM_CODEC = StreamCodec.of(
        EnderChannelSyncPacket::encode,
        EnderChannelSyncPacket::decode
    );

    public static EnderChannelSyncPacket fromRegistry(MinecraftServer server, EnderChannelKey channel, int channelSize,
                                                       List<EnderChannelEntry> rawEntries) {
        List<EnderChannelClientCache.EntryDisplay> displays = new ArrayList<>(rawEntries.size());
        for (EnderChannelEntry e : rawEntries) {
            String playerName = null;
            String ck = e.containerKey();
            if (ck != null && ck.startsWith("player_") && server != null) {
                UUID pid = parsePlayerUuid(ck);
                if (pid != null) {
                    ServerPlayer p = server.getPlayerList().getPlayer(pid);
                    if (p != null) playerName = p.getGameProfile().getName();
                }
            }
            displays.add(new EnderChannelClientCache.EntryDisplay(
                e.itemType(),
                e.sourceDim() != null ? e.sourceDim().location().toString() : null,
                e.sourcePos(),
                e.sourceSlot(),
                e.containerKey(),
                playerName
            ));
        }
        return new EnderChannelSyncPacket(channel, channelSize, displays);
    }

    /** 从 "player_<uuid>" / "player_<uuid>_ender_chest" 解析玩家 UUID，失败返回 null。 */
    private static UUID parsePlayerUuid(String containerKey) {
        String s = containerKey.substring("player_".length());
        if (s.endsWith("_ender_chest")) {
            s = s.substring(0, s.length() - "_ender_chest".length());
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void encode(FriendlyByteBuf buf, EnderChannelSyncPacket pkt) {
        writeChannelKey(buf, pkt.channel);
        buf.writeVarInt(pkt.channelSize);
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
            if (e.containerKey() != null) {
                buf.writeBoolean(true);
                buf.writeUtf(e.containerKey());
            } else {
                buf.writeBoolean(false);
            }
            if (e.playerName() != null) {
                buf.writeBoolean(true);
                buf.writeUtf(e.playerName());
            } else {
                buf.writeBoolean(false);
            }
        }
    }

    private static EnderChannelSyncPacket decode(FriendlyByteBuf buf) {
        EnderChannelKey channel = readChannelKey(buf);
        int channelSize = buf.readVarInt();
        int entryCount = buf.readVarInt();
        List<EnderChannelClientCache.EntryDisplay> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            String itemType = buf.readUtf();
            String dimKey = buf.readBoolean() ? buf.readUtf() : null;
            BlockPos sourcePos = buf.readBoolean() ? buf.readBlockPos() : null;
            int sourceSlot = buf.readVarInt();
            String containerKey = buf.readBoolean() ? buf.readUtf() : null;
            String playerName = buf.readBoolean() ? buf.readUtf() : null;
            entries.add(new EnderChannelClientCache.EntryDisplay(itemType, dimKey, sourcePos, sourceSlot, containerKey, playerName));
        }
        return new EnderChannelSyncPacket(channel, channelSize, entries);
    }

    /** 频道键编码：hasOwner 标志 +（可选）UUID + 堆叠数 VarInt。 */
    private static void writeChannelKey(FriendlyByteBuf buf, EnderChannelKey key) {
        UUID owner = key.owner();
        if (owner != null) {
            buf.writeBoolean(true);
            buf.writeUUID(owner);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeVarInt(key.count());
    }

    private static EnderChannelKey readChannelKey(FriendlyByteBuf buf) {
        UUID owner = buf.readBoolean() ? buf.readUUID() : null;
        int count = buf.readVarInt();
        return new EnderChannelKey(owner, count);
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
                packet.entries
            );
        });
    }
}
