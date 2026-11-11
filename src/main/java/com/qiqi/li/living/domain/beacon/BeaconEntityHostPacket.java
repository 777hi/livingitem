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
 * S2C：「哪些实体容器宿主身上有已激活的活信标」同步包（联机可见 · D-container-25）。
 *
 * <p>实体宿主（船 / 矿车 / 生物背包 / 掉落物）走本包（实体 UUID）—— 客户端拿 UUID
 * 查实体即可拿到实时位置（实体同步本来就带）。箱子宿主走 {@link BeaconHostPacket}（位置）、
 * 玩家宿主走 {@link BeaconPlayerHostPacket}（玩家 UUID）。</p>
 *
 * <p>⚠️ <b>没有「自己那份不发」</b>：信标在宿主实体身上，主人的本机物品栏里没有它
 * ⇒ 所有跟踪该实体的观察者（含主人）都靠本包。</p>
 *
 * @param dimension 所在维度（客户端残留保护）
 * @param entries   本包覆盖的全部条目；客户端整体替换缓存
 */
public record BeaconEntityHostPacket(
    ResourceLocation dimension,
    List<Entry> entries
) implements CustomPacketPayload {

    /**
     * 一个实体宿主 + 其最高激活等级（一实体多枚信标 ⇒ 取最高，一实体一条光束，与玩家口径一致）
     * + 该信标的容器玻璃色序列。
     *
     * <p>⚠️ <b>世界玻璃的颜色不下发</b>：U3 方案 A 下取自世界方块，客户端本地现算；
     * <b>容器槽位里的玻璃必须下发</b>——客户端不开宿主的 GUI 就看不到内容。</p>
     */
    public record Entry(UUID entityId, int level, List<Integer> glassColors) {
    }

    public static final Type<BeaconEntityHostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingMod.ID, "beacon_entity_host"));

    public static final StreamCodec<FriendlyByteBuf, BeaconEntityHostPacket> STREAM_CODEC =
        StreamCodec.of(BeaconEntityHostPacket::encode, BeaconEntityHostPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, BeaconEntityHostPacket pkt) {
        buf.writeResourceLocation(pkt.dimension);
        buf.writeVarInt(pkt.entries.size());
        for (Entry entry : pkt.entries) {
            buf.writeUUID(entry.entityId());
            buf.writeVarInt(entry.level());
            buf.writeVarInt(entry.glassColors().size());
            for (int color : entry.glassColors()) {
                buf.writeVarInt(color);
            }
        }
    }

    /** 解码容量上限（防御脏包：负数 / 超大 varint 不许直接进 ArrayList 构造 ⇒ 不许 OOM）。 */
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_COLORS = 256;

    private static int readClamped(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        return n < 0 ? 0 : Math.min(n, max);
    }

    private static BeaconEntityHostPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = readClamped(buf, MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID entityId = buf.readUUID();
            int level = buf.readVarInt();
            int colors = readClamped(buf, MAX_COLORS);
            List<Integer> glassColors = new ArrayList<>(colors);
            for (int c = 0; c < colors; c++) {
                glassColors.add(buf.readVarInt());
            }
            entries.add(new Entry(entityId, level, glassColors));
        }
        return new BeaconEntityHostPacket(dimension, entries);
    }

    public static void handle(BeaconEntityHostPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            BeaconEntityHostClientCache.update(packet.dimension(), packet.entries()));
    }
}
