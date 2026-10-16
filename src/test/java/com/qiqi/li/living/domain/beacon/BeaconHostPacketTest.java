package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * BeaconHostPacket 编解码往返测试（S2C：箱子宿主位置 + 等级 + 容器玻璃色）。
 */
class BeaconHostPacketTest {

    @Test
    @DisplayName("往返一致（维度 + 位置 + 等级 + 容器玻璃色）")
    void roundTrip() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        var pkt = new BeaconHostPacket(
            ResourceLocation.withDefaultNamespace("overworld"),
            List.of(new BeaconHostPacket.Entry(new BlockPos(12, 64, 34), 3,
                    List.of(0xFFC41420, 0xFF3C44AA)),
                new BeaconHostPacket.Entry(new BlockPos(-5, 70, 100), 1)));

        BeaconHostPacket.STREAM_CODEC.encode(buf, pkt);
        var decoded = BeaconHostPacket.STREAM_CODEC.decode(buf);

        assertEquals(pkt.dimension(), decoded.dimension());
        assertEquals(pkt.entries().size(), decoded.entries().size());
        assertEquals(pkt.entries(), decoded.entries());
        assertEquals(List.of(0xFFC41420, 0xFF3C44AA), decoded.entries().get(0).glassColors());
        assertEquals(List.of(), decoded.entries().get(1).glassColors());
    }

    @Test
    @DisplayName("空列表往返一致")
    void emptyEntries() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        var pkt = new BeaconHostPacket(ResourceLocation.withDefaultNamespace("the_nether"), List.of());

        BeaconHostPacket.STREAM_CODEC.encode(buf, pkt);
        assertEquals(pkt.entries(), BeaconHostPacket.STREAM_CODEC.decode(buf).entries());
    }
}
