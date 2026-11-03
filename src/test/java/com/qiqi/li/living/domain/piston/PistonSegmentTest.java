package com.qiqi.li.living.domain.piston;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;
import com.qiqi.li.living.runtime.RuntimeSegmentType;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.network.LivingItemSyncPacket;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PistonSegmentTest {

    @ParameterizedTest
    @CsvSource({"false,0,-1", "true,0,-1", "true,0,1", "true,-1,0", "true,1,0"})
    void runtimeRoundTripsWithoutItemState(boolean extended, int x, int y) {
        var state = new PistonRuntime(extended, new Pos2D(x, y));
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PistonSegment.INSTANCE.codec().encode(buf, state);
            assertEquals(state, PistonSegment.INSTANCE.codec().decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void typeIdIsStableAndFunctionIsGloballyEnabled() {
        assertEquals("piston", PistonSegment.INSTANCE.id());
        assertTrue(LivingItemManager.getAllFunctions().stream()
                .anyMatch(function -> function.getFunctionId().equals(LivingPistonFunction.ID)),
            "2026-11-03 G3 收口后已全局启用（生产 commonSetup 在测试 JVM 同样生效）");
    }

    @Test
    void existingPacketCodecCarriesPistonSegment() {
        List<RuntimeSegmentType<?>> previous = new ArrayList<>();
        for (String id : RuntimeSegmentRegistry.ids()) previous.add(RuntimeSegmentRegistry.byId(id));
        RuntimeSegmentRegistry.clearForTest();
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            RuntimeSegmentRegistry.register(PistonSegment.INSTANCE);
            var state = new PistonRuntime(true, Pos2D.LEFT);
            var packet = LivingItemSyncPacket.of("player_test", Map.of(4,
                RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE, state)));
            LivingItemSyncPacket.STREAM_CODEC.encode(buf, packet);
            var restored = LivingItemSyncPacket.STREAM_CODEC.decode(buf);
            assertEquals(packet.containerKey(), restored.containerKey());
            assertEquals(state, restored.slotData().get(4).get(PistonSegment.INSTANCE));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
            RuntimeSegmentRegistry.clearForTest();
            previous.forEach(RuntimeSegmentRegistry::register);
        }
    }
}
