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
     * 一个玩家宿主 + 其最高激活等级（一个玩家多枚信标 ⇒ 取最高）+ 该信标的容器玻璃色序列。
     *
     * <p>⚠️ <b>世界玻璃的颜色不下发</b>：U3 方案 A 下取自世界方块，客户端本地就能算（含别人的
     * 光束）。但<b>容器槽位里的玻璃必须下发</b>——客户端不开别人的背包 GUI 就看不到内容
     * （2026-10-16 用户需求「信标上方槽位的玻璃也影响颜色」的联机侧）。</p>
     *
     * @param glassColors 该玩家最高等级信标同列向上的玻璃色（自下而上；空 = 无）
     */
    public record Entry(UUID playerId, int level, List<Integer> glassColors) {

        public Entry(UUID playerId, int level) {
            this(playerId, level, List.of());
        }
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
            buf.writeVarInt(entry.glassColors().size());
            for (int color : entry.glassColors()) {
                buf.writeVarInt(color);
            }
        }
    }

    private static BeaconPlayerHostPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID playerId = buf.readUUID();
            int level = buf.readVarInt();
            int colors = buf.readVarInt();
            List<Integer> glassColors = new ArrayList<>(colors);
            for (int c = 0; c < colors; c++) {
                glassColors.add(buf.readVarInt());
            }
            entries.add(new Entry(playerId, level, glassColors));
        }
        return new BeaconPlayerHostPacket(dimension, entries);
    }

    public static void handle(BeaconPlayerHostPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            BeaconPlayerHostClientCache.update(packet.dimension(), packet.entries()));
    }
}
