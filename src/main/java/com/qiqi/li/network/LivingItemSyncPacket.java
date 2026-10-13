package com.qiqi.li.network;

import com.qiqi.li.living.container.ContainerKind;
import com.qiqi.li.living.container.ContainerKeys;
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
 *   kind (varint ordinal)          ← 2026-10-13 新增：容器种类，客户端据此分流，不再靠键前缀猜
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
 *
 * <p>⭐ <b>{@code kind} 字段为什么必须存在</b>（2026-10-13）：客户端曾按「是不是
 * {@code InventoryScreen}」推断容器种类，而服务端按<b>键前缀</b>判断 —— 两套判据对末影箱
 * （{@code player_<uuid>_ender_chest}）给出<b>相反</b>结论 ⇒ 读写分流错位、数据永远读不到。
 * ⇒ 改由服务端（键的产生方）在包里带上种类，客户端只做路由（与 {@code FluidFlowSyncPacket}
 * 的 {@code RenderTarget} 同款做法，那边 2026-10-06 已用同样方式解决过同一个坑）。</p>
 */
public record LivingItemSyncPacket(
    String containerKey,
    Map<Integer, RuntimeSegments> slotData,
    ContainerKind kind
) implements CustomPacketPayload {

    /**
     * 便利构造：种类由键解析（唯一入口 {@link ContainerKeys#kindOf}）。
     *
     * <p>生产与测试都走这里 —— 键是「身份」的权威字符串形式，解析规则只在
     * {@link ContainerKeys} 一处维护。</p>
     */
    public static LivingItemSyncPacket of(String containerKey, Map<Integer, RuntimeSegments> slotData) {
        return new LivingItemSyncPacket(containerKey, slotData, ContainerKeys.kindOf(containerKey));
    }

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
        buf.writeVarInt(kindOf(pkt).ordinal());
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
        ContainerKind kind = kindFromOrdinal(buf.readVarInt());
        int slotCount = buf.readVarInt();
        Map<Integer, RuntimeSegments> slotData = new HashMap<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            int slot = buf.readVarInt();
            slotData.put(slot, decodeRuntimeData(buf));
        }
        return new LivingItemSyncPacket(containerKey, slotData, kind);
    }

    /** 编码用：种类缺失（未知键形）按世界容器处理（最保守 —— 绝不污染背包缓存）。 */
    private static ContainerKind kindOf(LivingItemSyncPacket pkt) {
        return pkt.kind() != null ? pkt.kind() : ContainerKind.WORLD_BLOCK;
    }

    /** 解码用：ordinal 越界（版本错位）回退世界容器 —— 同 {@code FluidFlowSyncPacket} 的兜底口径。 */
    private static ContainerKind kindFromOrdinal(int ordinal) {
        ContainerKind[] values = ContainerKind.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ContainerKind.WORLD_BLOCK;
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
            // ⭐ 按服务端下发的种类分流（2026-10-13 修）——不再靠键前缀猜：
            // 末影箱与背包前缀相同（player_…），前缀判断会把末影箱误送进背包缓存，
            // 而 tooltip 读取端对末影箱界面走「非背包」路径（get(slot)）⇒ 读写错位、永远读不到。
            // 其余种类（末影箱 / 世界容器 / 掉落物）都进通用缓存 —— 与读取端的两分法严格对齐。
            if (packet.kind() == ContainerKind.PLAYER_INV) {
                LivingItemClientCache.updatePlayer(packet.slotData);
            } else {
                LivingItemClientCache.update(packet.containerKey, packet.slotData);
            }
        });
    }
}
