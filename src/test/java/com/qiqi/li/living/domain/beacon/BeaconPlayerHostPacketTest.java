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
 * BeaconPlayerHostPacket 编解码往返测试（S2C：玩家宿主 UUID + 等级 + 容器玻璃色）。
 */
class BeaconPlayerHostPacketTest {

    @Test
    @DisplayName("往返一致（维度 + UUID + 等级 + 容器玻璃色）")
    void roundTrip() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var pkt = new BeaconPlayerHostPacket(
            ResourceLocation.withDefaultNamespace("overworld"),
            List.of(new BeaconPlayerHostPacket.Entry(a, 4, List.of(0xFFC41420)),
                new BeaconPlayerHostPacket.Entry(b, 1)));

        BeaconPlayerHostPacket.STREAM_CODEC.encode(buf, pkt);
        var decoded = BeaconPlayerHostPacket.STREAM_CODEC.decode(buf);

        assertEquals(pkt.dimension(), decoded.dimension());
        assertEquals(pkt.entries(), decoded.entries());
        assertEquals(List.of(0xFFC41420), decoded.entries().get(0).glassColors());
        assertEquals(List.of(), decoded.entries().get(1).glassColors());
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
