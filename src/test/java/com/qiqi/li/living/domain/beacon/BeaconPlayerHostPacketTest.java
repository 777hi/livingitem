package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * BeaconPlayerHostPacket 编解码往返测试（S2C：玩家宿主 UUID + 等级）。
 */
class BeaconPlayerHostPacketTest {

    @Test
    @DisplayName("往返一致（维度 + UUID + 等级 + 颜色）")
    void roundTrip() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var pkt = new BeaconPlayerHostPacket(
            ResourceLocation.withDefaultNamespace("overworld"),
            List.of(new BeaconPlayerHostPacket.Entry(a, 4, 0xFF00FF00),
                new BeaconPlayerHostPacket.Entry(b, 1, 0xFFFFFF)));

        BeaconPlayerHostPacket.STREAM_CODEC.encode(buf, pkt);
        var decoded = BeaconPlayerHostPacket.STREAM_CODEC.decode(buf);

        assertEquals(pkt.dimension(), decoded.dimension());
        assertEquals(pkt.entries(), decoded.entries());
    }

    @Test
    @DisplayName("空列表往返一致")
    void emptyEntries() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        var pkt = new BeaconPlayerHostPacket(ResourceLocation.withDefaultNamespace("the_end"), List.of());

        BeaconPlayerHostPacket.STREAM_CODEC.encode(buf, pkt);
        assertEquals(pkt.entries(), BeaconPlayerHostPacket.STREAM_CODEC.decode(buf).entries());
    }
}
