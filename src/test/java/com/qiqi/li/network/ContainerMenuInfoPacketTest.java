package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.qiqi.li.living.container.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.Test;

class ContainerMenuInfoPacketTest {
    private ContainerMenuInfoPacket roundTrip(ContainerMenuInfoPacket value) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ContainerMenuInfoPacket.STREAM_CODEC.encode(buf, value);
            return ContainerMenuInfoPacket.STREAM_CODEC.decode(buf);
        } finally { buf.release(); }
    }
    @Test void availableInfo_roundTripsAllKindsAndSources() {
        for (var kind : ContainerKind.values()) for (var source : ContainerLayout.Source.values()) {
            var info = new ContainerMenuInfo(ContainerMenuInfo.Status.AVAILABLE, kind, 30,
                new ContainerLayout(5, source), "test:container");
            var packet = new ContainerMenuInfoPacket(4, 123L, info);
            assertEquals(packet, roundTrip(packet));
        }
    }
    @Test void unavailableAndNonHost_roundTripSeparately() {
        assertEquals(ContainerMenuInfo.UNAVAILABLE, roundTrip(new ContainerMenuInfoPacket(1, 2, ContainerMenuInfo.UNAVAILABLE)).info());
        assertEquals(ContainerMenuInfo.NON_HOST, roundTrip(new ContainerMenuInfoPacket(1, 2, ContainerMenuInfo.NON_HOST)).info());
    }
    @Test void request_roundTripsCorrelation() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new ContainerMenuInfoRequestPacket(4, 987L);
            ContainerMenuInfoRequestPacket.STREAM_CODEC.encode(buf, packet);
            assertEquals(packet, ContainerMenuInfoRequestPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }
    @Test void requests_areRateLimitedPerPlayer() {
        var player = java.util.UUID.randomUUID();
        assertTrue(ContainerMenuInfoRequestPacket.allowRequest(player, 100));
        assertFalse(ContainerMenuInfoRequestPacket.allowRequest(player, 109));
        assertTrue(ContainerMenuInfoRequestPacket.allowRequest(player, 110));
        assertTrue(ContainerMenuInfoRequestPacket.allowRequest(java.util.UUID.randomUUID(), 100));
    }
    @Test void requestForOldMenu_isIgnoredBeforeResolution() {
        var player = mock(ServerPlayer.class);
        var menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu;
        var context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return java.util.concurrent.CompletableFuture.completedFuture(null); })
            .when(context).enqueueWork(any(Runnable.class));
        ContainerMenuInfoRequestPacket.handle(new ContainerMenuInfoRequestPacket(9, 1), context);
        verify(context, never()).reply(any());
        verify(player, never()).level();
    }
    @Test void logout_releasesRateLimitEntry() {
        var id = java.util.UUID.randomUUID();
        var player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(id);
        assertTrue(ContainerMenuInfoRequestPacket.allowRequest(id, 100));
        ContainerMenuInfoRequestPacket.onPlayerLogout(
            new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        assertTrue(ContainerMenuInfoRequestPacket.allowRequest(id, 101));
    }

    @Test void validCurrentMenu_requestRepliesWithCorrelatedInfo() {
        var player = mock(ServerPlayer.class);
        var menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu;
        doReturn(net.minecraft.world.inventory.MenuType.CRAFTING).when(menu).getType();
        when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());
        var level = mock(net.minecraft.world.level.Level.class);
        when(player.level()).thenReturn(level);
        when(level.getGameTime()).thenReturn(100L);
        var context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return java.util.concurrent.CompletableFuture.completedFuture(null); })
            .when(context).enqueueWork(any(Runnable.class));
        ContainerMenuInfoRequestPacket.handle(new ContainerMenuInfoRequestPacket(menu.containerId, 42), context);
        verify(context).reply(new ContainerMenuInfoPacket(menu.containerId, 42, ContainerMenuInfo.NON_HOST));
    }

    @Test void invalidGeometry_rejectedAndPartialRowRoundedUp() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerLayout(0, ContainerLayout.Source.FIXED));
        var info = new ContainerMenuInfo(ContainerMenuInfo.Status.AVAILABLE, ContainerKind.WORLD_BLOCK,
            10, new ContainerLayout(3, ContainerLayout.Source.RULE), "");
        assertEquals(4, info.rows());
    }
}
