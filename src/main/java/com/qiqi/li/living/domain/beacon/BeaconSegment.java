package com.qiqi.li.living.domain.beacon;

import java.util.Arrays;
import java.util.Objects;

import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活信标运行时数据片段（设计稿 §2.6）。
 *
 * <p>把「激活等级 / 9 列柱高 / 免费再生档位」的展示数据交给 runtime 机制（档 2）——
 * 等级每 tick 由下方底座现算，<b>严禁写 DataComponent</b>（遥测铁律，见
 * {@code tooltip-system.md} §7-1）。tooltip 实时行与（Phase 4）F3+H 小金字塔面板
 * 共用这一份数据。</p>
 *
 * <p>⚠️ {@link #id()} 进网络包，**发布后不可改名**。⚠️ 柱高是 long[] ⇒ 手写
 * equals/hashCode（数组按内容比），否则稳态下 equals 永不成立 ⇒ 每 tick 都脏写发包。</p>
 */
public final class BeaconSegment implements RuntimeSegmentType<BeaconSegment.BeaconRuntime> {

    public static final BeaconSegment INSTANCE = new BeaconSegment();

    private BeaconSegment() {
    }

    @Override
    public String id() {
        return "beacon";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, BeaconRuntime> codec() {
        return BeaconRuntime.STREAM_CODEC;
    }

    /**
     * 免费再生档位（满级白送的三档规则：有空槽⇒II / 无空槽全 I⇒I / 无空槽且已加倍⇒无）。
     *
     * <p>⚠️ Phase 2 恒 {@link #NONE} —— 三档判定依赖效果配置（Phase 3 的 5 矿石槽），
     * 接入后由 tick 计算填入。</p>
     */
    public enum RegenTier {
        NONE, I, II;

        public static RegenTier fromOrdinal(int ordinal) {
            RegenTier[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
        }
    }

    /**
     * 活信标运行时快照。
     *
     * @param level         激活等级 0~4（0 = 未激活）
     * @param columnHeights 9 列柱高（下标 {@link BeaconPyramid#CENTER} 为信标本列，越界列 0）
     * @param regenTier     免费再生档位（Phase 2 恒 NONE）
     */
    public record BeaconRuntime(int level, long[] columnHeights, RegenTier regenTier) {

        public static final BeaconRuntime EMPTY =
            new BeaconRuntime(0, new long[BeaconPyramid.COLUMNS], RegenTier.NONE);

        /**
         * 编解码：{@code varint level} + 9×{@code varLong 柱高} + {@code varint 再生档}。
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, BeaconRuntime> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BeaconRuntime decode(RegistryFriendlyByteBuf buf) {
                    int level = buf.readVarInt();
                    long[] heights = new long[BeaconPyramid.COLUMNS];
                    for (int i = 0; i < heights.length; i++) {
                        heights[i] = buf.readVarLong();
                    }
                    return new BeaconRuntime(level, heights, RegenTier.fromOrdinal(buf.readVarInt()));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BeaconRuntime r) {
                    buf.writeVarInt(r.level());
                    for (long h : r.columnHeights()) {
                        buf.writeVarLong(h);
                    }
                    buf.writeVarInt(r.regenTier().ordinal());
                }
            };

        /** 数组字段必须按内容比较 —— 否则稳态每 tick 都判脏（发包风暴）。 */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BeaconRuntime other)) return false;
            return level == other.level
                && regenTier == other.regenTier
                && Arrays.equals(columnHeights, other.columnHeights);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level, regenTier, Arrays.hashCode(columnHeights));
        }
    }
}
