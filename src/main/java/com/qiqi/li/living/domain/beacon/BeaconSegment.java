package com.qiqi.li.living.domain.beacon;

import java.util.Arrays;
import java.util.Objects;

import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活信标运行时数据片段（设计稿 §2.6）。
 *
 * <p>把「激活等级 / 9 列柱高 / 列存在性」的展示数据交给 runtime 机制（档 2）——
 * 等级每 tick 由下方底座现算，<b>严禁写 DataComponent</b>（遥测铁律，见
 * {@code tooltip-system.md} §7-1）。tooltip 实时行与 9 柱小金字塔面板（Phase 4）
 * 共用这一份数据。</p>
 *
 * <p>⚠️ <b>不含再生</b>：满级再生是<b>等级的直接函数</b>（L4 ⇒ 再生 II，2026-11-08 用户拍板
 * 「满级无条件奖励、不迎合原版」）——传它只会多一个可能与等级行不一致的副本。</p>
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
     * 活信标运行时快照。
     *
     * @param level         激活等级 0~4（0 = 未激活）
     * @param columnHeights 9 列柱高（下标 {@link BeaconPyramid#CENTER} 为信标本列，越界列 0）
     * @param columnExists  9 列存在性（下标同上；false = 该列越出容器边界 ⇒ 金字塔被钳制）。
     *                      Phase 4 新增：区分「空槽（差材料）」与「该列不存在（别填了）」——
     *                      没有它，信标贴容器边缘时面板会把永远填不上的列显示成缺口。
     */
    public record BeaconRuntime(int level, long[] columnHeights, boolean[] columnExists) {

        /**
         * 编解码：{@code varint level} + 9×{@code varLong 柱高} + {@code varint 列存在位图}。
         * 位图 bit {@code c} = 列 {@code c} 是否存在（9 位 ⇒ ≤ 511）。
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
                    int mask = buf.readVarInt();
                    boolean[] exists = new boolean[BeaconPyramid.COLUMNS];
                    for (int c = 0; c < exists.length; c++) {
                        exists[c] = ((mask >> c) & 1) != 0;
                    }
                    return new BeaconRuntime(level, heights, exists);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BeaconRuntime r) {
                    buf.writeVarInt(r.level());
                    for (long h : r.columnHeights()) {
                        buf.writeVarLong(h);
                    }
                    boolean[] exists = r.columnExists();
                    int mask = 0;
                    for (int c = 0; c < BeaconPyramid.COLUMNS; c++) {
                        if (c < exists.length && exists[c]) {
                            mask |= 1 << c;
                        }
                    }
                    buf.writeVarInt(mask);
                }
            };

        /** 数组字段必须按内容比较 —— 否则稳态每 tick 都判脏（发包风暴）。 */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BeaconRuntime other)) return false;
            return level == other.level
                && Arrays.equals(columnHeights, other.columnHeights)
                && Arrays.equals(columnExists, other.columnExists);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level,
                Arrays.hashCode(columnHeights), Arrays.hashCode(columnExists));
        }
    }
}
