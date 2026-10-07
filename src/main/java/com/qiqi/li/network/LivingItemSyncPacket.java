package com.qiqi.li.network;

import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;
import com.qiqi.li.living.runtime.RuntimeSegmentType;
import com.qiqi.li.living.runtime.RuntimeSegments;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

/**
 * S2C 活物品运行时数据同步包。
 *
 * <p>将服务端 {@link com.qiqi.li.living.runtime.ContainerRuntimeCache} 中的
 * 运行时数据下发到客户端，用于 Tooltip 渲染。数据不经过 DataComponent，
 * 因此不影响物品堆叠。</p>
 *
 * <p><b>本包不认识任何具体片段</b>（档 2）：每段的载荷由
 * {@code segmentId} 在 {@link RuntimeSegmentRegistry} 查到的 codec 编解码。
 * 新增一种遥测只需在领域侧实现 {@link RuntimeSegmentType} 并登记 —— 网络包无需改动。</p>
 *
 * <p>编码格式：</p>
 * <pre>
 *   containerKey (string)
 *   slotCount (varint)
 *   for each slot:
 *     slotIndex (varint)
 *     segmentCount (varint)
 *     for each segment:
 *       segmentId (string)
 *       payload (由该 id 登记的 codec 编解码)
 * </pre>
 *
 * <p>⚠️ <b>与改造前（flags 字节 + 三组硬编码字段）的线上格式不同</b> —— 旧客户端/新服务端
 * 不互通。本模组 alpha 阶段不做旧存档/跨版本兼容，故可接受。</p>
 */
public record LivingItemSyncPacket(
    String containerKey,
    Map<Integer, RuntimeSegments> slotData
) implements CustomPacketPayload {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("living_item", "living_item_sync");
    public static final CustomPacketPayload.Type<LivingItemSyncPacket> TYPE =
        new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, LivingItemSyncPacket> STREAM_CODEC =
        StreamCodec.of(LivingItemSyncPacket::encode, LivingItemSyncPacket::decode);

    @Override
    public CustomPacketPayload.Type<LivingItemSyncPacket> type() {
        return TYPE;
    }

    // ── 编码 ────────────────────────────────────────────────

    private static void encode(FriendlyByteBuf buf, LivingItemSyncPacket pkt) {
        buf.writeUtf(pkt.containerKey);
        buf.writeVarInt(pkt.slotData.size());
        for (var entry : pkt.slotData.entrySet()) {
            buf.writeVarInt(entry.getKey());
            encodeRuntimeData(buf, entry.getValue());
        }
    }

    private static void encodeRuntimeData(FriendlyByteBuf buf, RuntimeSegments data) {
        RegistryFriendlyByteBuf regBuf = (RegistryFriendlyByteBuf) buf;
        Set<String> ids = data.ids();
        buf.writeVarInt(ids.size());
        for (String id : ids) {
            RuntimeSegmentType<?> type = RuntimeSegmentRegistry.byId(id);
            if (type == null) {
                throw new IllegalStateException("未登记的 runtime 片段 id: " + id);
            }
            buf.writeUtf(id);
            encodeSegment(regBuf, type, data.rawById(id));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void encodeSegment(RegistryFriendlyByteBuf buf,
                                          RuntimeSegmentType<T> type, Object value) {
        type.codec().encode(buf, (T) value);
    }

    // ── 解码 ────────────────────────────────────────────────

    private static LivingItemSyncPacket decode(FriendlyByteBuf buf) {
        String containerKey = buf.readUtf();
        int slotCount = buf.readVarInt();
        Map<Integer, RuntimeSegments> slotData = new HashMap<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            int slot = buf.readVarInt();
            slotData.put(slot, decodeRuntimeData(buf));
        }
        return new LivingItemSyncPacket(containerKey, slotData);
    }

    private static RuntimeSegments decodeRuntimeData(FriendlyByteBuf buf) {
        RegistryFriendlyByteBuf regBuf = (RegistryFriendlyByteBuf) buf;
        int segmentCount = buf.readVarInt();
        RuntimeSegments result = RuntimeSegments.EMPTY;
        for (int i = 0; i < segmentCount; i++) {
            String id = buf.readUtf();
            RuntimeSegmentType<?> type = RuntimeSegmentRegistry.byId(id);
            if (type == null) {
                throw new IllegalStateException("未登记的 runtime 片段 id: " + id);
            }
            result = decodeSegmentInto(regBuf, result, type);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> RuntimeSegments decodeSegmentInto(RegistryFriendlyByteBuf buf,
                                                         RuntimeSegments acc,
                                                         RuntimeSegmentType<T> type) {
        T value = type.codec().decode(buf);
        return acc.with(type, value);
    }

    // ── 客户端处理 ───────────────────────────────────────────

    public static void handle(LivingItemSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.containerKey().startsWith("player_")) {
                LivingItemClientCache.updatePlayer(packet.slotData);
            } else {
                LivingItemClientCache.update(packet.containerKey, packet.slotData);
            }
        });
    }
}
