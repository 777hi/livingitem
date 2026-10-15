package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * BeaconSegment 编解码与相等性测试。
 *
 * <p>线格式：{@code varint level} + 9×{@code varLong 柱高} + {@code varint 再生档}。
 * ⚠️ 手写 equals 是「稳态不脏写」的前提（数组字段按内容比）。</p>
 */
class BeaconSegmentTest {

    @Test
    @DisplayName("id = beacon（线格式契约，发布后不可改名）")
    void id() {
        assertEquals("beacon", BeaconSegment.INSTANCE.id());
    }

    @Test
    @DisplayName("编解码往返一致（等级 + 9 柱高 + 再生档 NONE）")
    void roundTrip() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var rt = new BeaconSegment.BeaconRuntime(3,
            new long[]{0, 7, 12, 15, 15, 15, 12, 7, 0}, BeaconSegment.RegenTier.NONE);

        BeaconSegment.BeaconRuntime.STREAM_CODEC.encode(buf, rt);
        var decoded = BeaconSegment.BeaconRuntime.STREAM_CODEC.decode(buf);

        assertEquals(rt, decoded);
        assertEquals(3, decoded.level());
        assertArrayEquals(rt.columnHeights(), decoded.columnHeights());
        assertEquals(BeaconSegment.RegenTier.NONE, decoded.regenTier());
    }

    @Test
    @DisplayName("再生档 II 往返一致")
    void roundTripRegenTier() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var rt = new BeaconSegment.BeaconRuntime(4,
            new long[]{9, 16, 21, 24, 24, 24, 21, 16, 9}, BeaconSegment.RegenTier.II);

        BeaconSegment.BeaconRuntime.STREAM_CODEC.encode(buf, rt);
        assertEquals(rt, BeaconSegment.BeaconRuntime.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("equals 按数组内容比 + hashCode 一致（稳态不脏写的前提）")
    void equalsByContent() {
        var a = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 3, 0, 0, 0},
            BeaconSegment.RegenTier.NONE);
        var b = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 3, 0, 0, 0},
            BeaconSegment.RegenTier.NONE);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        var c = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 4, 0, 0, 0},
            BeaconSegment.RegenTier.NONE);
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("RegenTier.fromOrdinal 越界回退 NONE")
    void regenTierFallback() {
        assertEquals(BeaconSegment.RegenTier.NONE, BeaconSegment.RegenTier.fromOrdinal(-1));
        assertEquals(BeaconSegment.RegenTier.NONE, BeaconSegment.RegenTier.fromOrdinal(99));
        assertEquals(BeaconSegment.RegenTier.II, BeaconSegment.RegenTier.fromOrdinal(2));
    }
}
