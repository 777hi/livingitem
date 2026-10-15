package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * BeaconConfig 组件读写与编解码测试。
 */
class BeaconConfigTest {

    private static BeaconConfig sample() {
        return new BeaconConfig(List.of(
            new BeaconConfig.Entry(OreType.IRON, false),
            new BeaconConfig.Entry(OreType.EMERALD, true)));
    }

    @Nested
    @DisplayName("变更助手")
    class Helpers {

        @Test
        @DisplayName("add：追加 I 级槽；amplify：升级 II 不新增")
        void addAndAmplify() {
            var cfg = BeaconConfig.EMPTY.add(OreType.IRON);
            assertEquals(1, cfg.size());
            assertFalse(cfg.isAmplified(OreType.IRON));

            var up = cfg.amplify(OreType.IRON);
            assertEquals(1, up.size());
            assertTrue(up.isAmplified(OreType.IRON));
            // 原实例不被修改（不可变）
            assertFalse(cfg.isAmplified(OreType.IRON));
        }

        @Test
        @DisplayName("5 槽封顶 + contains")
        void full() {
            var cfg = BeaconConfig.EMPTY
                .add(OreType.IRON).add(OreType.GOLD).add(OreType.NETHERITE)
                .add(OreType.EMERALD).add(OreType.DIAMOND);
            assertTrue(cfg.isFull());
            assertTrue(cfg.contains(OreType.DIAMOND));
            assertTrue(cfg.contains(OreType.IRON));
        }
    }

    @Test
    @DisplayName("编解码往返一致（矿石 + 加倍标志）")
    void roundTrip() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var cfg = sample();
        BeaconConfig.STREAM_CODEC.encode(buf, cfg);
        assertEquals(cfg, BeaconConfig.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("空配置往返一致")
    void roundTripEmpty() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        BeaconConfig.STREAM_CODEC.encode(buf, BeaconConfig.EMPTY);
        assertEquals(BeaconConfig.EMPTY, BeaconConfig.STREAM_CODEC.decode(buf));
    }
}
