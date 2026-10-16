package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * BeaconSegment 编解码与相等性测试。
 *
 * <p>线格式：{@code varint level} + 9×{@code varLong 柱高} + {@code varint 列存在位图}
 * + {@code varint 再生档}。⚠️ 手写 equals 是「稳态不脏写」的前提（数组字段按内容比）。</p>
 */
class BeaconSegmentTest {

    private static boolean[] exists(boolean... values) {
        return values;
    }

    private static final boolean[] ALL_EXIST =
        exists(true, true, true, true, true, true, true, true, true);

    @Test
    @DisplayName("id = beacon（线格式契约，发布后不可改名）")
    void id() {
        assertEquals("beacon", BeaconSegment.INSTANCE.id());
    }

    @Test
    @DisplayName("编解码往返一致（等级 + 9 柱高 + 列存在 + 再生档 NONE）")
    void roundTrip() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var rt = new BeaconSegment.BeaconRuntime(3,
            new long[]{0, 7, 12, 15, 15, 15, 12, 7, 0}, ALL_EXIST,
            BeaconSegment.RegenTier.NONE);

        BeaconSegment.BeaconRuntime.STREAM_CODEC.encode(buf, rt);
        var decoded = BeaconSegment.BeaconRuntime.STREAM_CODEC.decode(buf);

        assertEquals(rt, decoded);
        assertEquals(3, decoded.level());
        assertArrayEquals(rt.columnHeights(), decoded.columnHeights());
        assertArrayEquals(ALL_EXIST, decoded.columnExists());
        assertEquals(BeaconSegment.RegenTier.NONE, decoded.regenTier());
    }

    @Test
    @DisplayName("列存在位图往返一致（信标贴左缘 ⇒ 前 2 列越界 false）")
    void roundTripColumnExistsMask() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var rt = new BeaconSegment.BeaconRuntime(1,
            new long[]{0, 0, 3, 6, 8, 8, 4, 0, 0},
            exists(false, false, true, true, true, true, true, true, true),
            BeaconSegment.RegenTier.NONE);

        BeaconSegment.BeaconRuntime.STREAM_CODEC.encode(buf, rt);
        var decoded = BeaconSegment.BeaconRuntime.STREAM_CODEC.decode(buf);

        assertEquals(rt, decoded);
        assertFalse(decoded.columnExists()[0]);
        assertFalse(decoded.columnExists()[1]);
        assertTrue(decoded.columnExists()[2]);
        assertTrue(decoded.columnExists()[8]);
    }

    @Test
    @DisplayName("再生档 II 往返一致")
    void roundTripRegenTier() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var rt = new BeaconSegment.BeaconRuntime(4,
            new long[]{9, 16, 21, 24, 24, 24, 21, 16, 9}, ALL_EXIST,
            BeaconSegment.RegenTier.II);

        BeaconSegment.BeaconRuntime.STREAM_CODEC.encode(buf, rt);
        assertEquals(rt, BeaconSegment.BeaconRuntime.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("equals 按数组内容比 + hashCode 一致（稳态不脏写的前提）")
    void equalsByContent() {
        var a = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 3, 0, 0, 0},
            ALL_EXIST, BeaconSegment.RegenTier.NONE);
        var b = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 3, 0, 0, 0},
            ALL_EXIST, BeaconSegment.RegenTier.NONE);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        var c = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 4, 0, 0, 0},
            ALL_EXIST, BeaconSegment.RegenTier.NONE);
        assertNotEquals(a, c);

        // 仅列存在位图不同也必须判不等（否则越界变化不脏写 ⇒ 面板不刷新）
        var d = new BeaconSegment.BeaconRuntime(1, new long[]{0, 0, 0, 3, 3, 3, 0, 0, 0},
            exists(false, false, false, true, true, true, false, false, false),
            BeaconSegment.RegenTier.NONE);
        assertNotEquals(a, d);
    }

    @Test
    @DisplayName("RegenTier.fromOrdinal 越界回退 NONE")
    void regenTierFallback() {
        assertEquals(BeaconSegment.RegenTier.NONE, BeaconSegment.RegenTier.fromOrdinal(-1));
        assertEquals(BeaconSegment.RegenTier.NONE, BeaconSegment.RegenTier.fromOrdinal(99));
        assertEquals(BeaconSegment.RegenTier.II, BeaconSegment.RegenTier.fromOrdinal(2));
    }
}
