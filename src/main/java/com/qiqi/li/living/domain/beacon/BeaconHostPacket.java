package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：「哪些箱子里有活信标」同步包（照 {@code LivingToolHostPacket} 的 K2 模式）。
 *
 * <h3>为什么需要它（现有 {@link LivingItemSyncPacket} 不行）</h3>
 * 遥测包只发给<b>正在开该容器 GUI 的玩家</b>；而箱子宿主的光束要"路过就能看见"
 * ⇒ 必须独立广播。只同步 <b>宿主半箱位置 + 激活等级 + 该枚信标同列向上的容器玻璃色</b>
 * （`glassColors`，2026-10-16 起；客户端不开 GUI 读不到容器内容）——**不泄露容器内其它物品**。
 *
 * <p>发送策略（见 {@link BeaconChestHost}）：按距离定向（R = 256）+ 内容去重
 * （等级 / 玻璃色变化/增删才发）。</p>
 *
 * @param dimension 所在维度，供客户端做残留保护（换维度 / 换存档时旧数据不串台）
 * @param entries   本包覆盖的全部条目；客户端<b>整体替换</b>缓存
 */
public record BeaconHostPacket(
    ResourceLocation dimension,
    List<Entry> entries
) implements CustomPacketPayload {

    /**
     * 一个宿主箱子（活信标所在半箱）+ 激活等级 + <b>信标同列向上的容器玻璃色序列</b>。
     *
     * <p>玻璃色必须随包下发：客户端不开 GUI 时看不到容器内容（§2.3 约束）⇒ 容器槽位里的
     * 染色玻璃无法像世界方块那样本地读。世界扫描的玻璃仍是客户端本地读（U3-A 不变）——
     * 两类来源在 {@code BeamColorSections} 里按「容器列在前、世界列在后」串联。</p>
     *
     * @param glassColors 自下而上的 ARGB 色列表（信标同列向上；空 = 无容器玻璃）
     */
    public record Entry(BlockPos pos, int level, List<Integer> glassColors) {

        public Entry(BlockPos pos, int level) {
            this(pos, level, List.of());
        }
    }

    public static final Type<BeaconHostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingMod.ID, "beacon_host"));

    public static final StreamCodec<FriendlyByteBuf, BeaconHostPacket> STREAM_CODEC =
        StreamCodec.of(BeaconHostPacket::encode, BeaconHostPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, BeaconHostPacket pkt) {
        buf.writeResourceLocation(pkt.dimension);
        buf.writeVarInt(pkt.entries.size());
        for (Entry entry : pkt.entries) {
            buf.writeBlockPos(entry.pos());
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

    private static BeaconHostPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = readClamped(buf, MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            int level = buf.readVarInt();
            int colors = readClamped(buf, MAX_COLORS);
            List<Integer> glassColors = new ArrayList<>(colors);
            for (int c = 0; c < colors; c++) {
                glassColors.add(buf.readVarInt());
            }
            entries.add(new Entry(pos, level, glassColors));
        }
        return new BeaconHostPacket(dimension, entries);
    }

    public static void handle(BeaconHostPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            BeaconHostClientCache.update(packet.dimension(), packet.entries()));
    }
}
