package com.qiqi.li.living.domain.hopper;

import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活漏斗冷却与槽位解析信息的运行时数据片段（档 2）。
 *
 * <p>把原先 {@code LivingItemRuntimeData.HopperRuntime} 与
 * {@code LivingItemSyncPacket} 里那一组字段收归本领域。</p>
 *
 * <p>⚠️ {@link #id()} 进网络包，**发布后不可改名**。</p>
 */
public final class HopperSegment implements RuntimeSegmentType<HopperSegment.HopperRuntime> {

    public static final HopperSegment INSTANCE = new HopperSegment();

    private HopperSegment() {}

    @Override
    public String id() {
        return "hopper";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, HopperSegment.HopperRuntime> codec() {
        return HopperRuntime.STREAM_CODEC;
    }

    /**
     * 活漏斗运行时：仅冷却与槽位解析信息。
     *
     * <p>（原 {@code LivingItemRuntimeData.HopperRuntime}，档 2 迁入本领域。）</p>
     *
     * @param cooldown 剩余冷却（tick）
     * @param slotInfo 槽位解析快照；可为 null（表示本次无解析信息）
     */
    public record HopperRuntime(int cooldown, ResolvedSlotData slotInfo) {

        public static final HopperRuntime EMPTY = new HopperRuntime(0, null);

        /**
         * 编解码 —— 与改造前 {@code encodeHopper/decodeHopper} **字节格式一致**：
         * {@code varint cooldown} + {@code bool hasSlot} + [5×int slotInfo]。
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, HopperRuntime> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public HopperRuntime decode(RegistryFriendlyByteBuf buf) {
                    int cooldown = buf.readVarInt();
                    ResolvedSlotData slot = buf.readBoolean() ? ResolvedSlotData.STREAM_CODEC.decode(buf) : null;
                    return new HopperRuntime(cooldown, slot);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, HopperRuntime h) {
                    buf.writeVarInt(h.cooldown());
                    if (h.slotInfo() != null) {
                        buf.writeBoolean(true);
                        ResolvedSlotData.STREAM_CODEC.encode(buf, h.slotInfo());
                    } else {
                        buf.writeBoolean(false);
                    }
                }
            };
    }
}
