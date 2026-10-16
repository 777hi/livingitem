package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：「哪些玩家背包里有已激活的活信标」同步包（联机可见 · 设计稿规则 6）。
 *
 * <p>箱子宿主走 {@link BeaconHostPacket}（位置），玩家宿主走本包（UUID）——
 * 客户端拿 UUID 查实体即可拿到实时位置与视线方向（实体旋转本来就同步）。</p>
 *
 * <p>⚠️ <b>自己那一份不发</b>：本机渲染更实时（照 {@code LivingToolPlayerSync} 惯例）。</p>
 *
 * @param dimension 所在维度（客户端残留保护）
 * @param entries   本包覆盖的全部条目；客户端整体替换缓存
 */
public record BeaconPlayerHostPacket(
    ResourceLocation dimension,
    List<Entry> entries
) implements CustomPacketPayload {

    /**
     * 一个玩家宿主 + 其最高激活等级（一个玩家多枚信标 ⇒ 取最高）+ 光束颜色（U3 方案 B）。
     *
     * <p>颜色必须随包下发：客户端读不到<b>别人背包</b>里"信标上方那一格"的染色玻璃。</p>
     */
    public record Entry(UUID playerId, int level, int color) {
    }

    public static final Type<BeaconPlayerHostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingMod.ID, "beacon_player_host"));

    public static final StreamCodec<FriendlyByteBuf, BeaconPlayerHostPacket> STREAM_CODEC =
        StreamCodec.of(BeaconPlayerHostPacket::encode, BeaconPlayerHostPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, BeaconPlayerHostPacket pkt) {
        buf.writeResourceLocation(pkt.dimension);
        buf.writeVarInt(pkt.entries.size());
        for (Entry entry : pkt.entries) {
            buf.writeUUID(entry.playerId());
            buf.writeVarInt(entry.level());
            buf.writeVarInt(entry.color());
        }
    }

    private static BeaconPlayerHostPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readUUID(), buf.readVarInt(), buf.readVarInt()));
        }
        return new BeaconPlayerHostPacket(dimension, entries);
    }

    public static void handle(BeaconPlayerHostPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            BeaconPlayerHostClientCache.update(packet.dimension(), packet.entries()));
    }
}
