package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.qiqi.li.living.config.GameplayConfig;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 来源锚点的自身优先、借位门控与真实世界/坐标成对约束。 */
class PortalPositionResolverTest {

    @Test
    void ownBlockUsesContextLevelAndCenterEvenWhenBorrowingDisabled() {
        ContainerContext ctx = mock(ContainerContext.class);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos pos = new BlockPos(8, 64, -3);
        when(ctx.getAnchor()).thenReturn(new ContainerAnchor.AtBlock(pos));
        when(ctx.getLevel()).thenReturn(level);
        try (MockedStatic<GameplayConfig> config = mockStatic(GameplayConfig.class)) {
            var origin = PortalPositionResolver.resolve(ctx).orElseThrow();
            assertSame(level, origin.level());
            assertEquals(new Vec3(8.5, 64.5, -2.5), origin.pos());
            config.verifyNoInteractions();
            verify(ctx, never()).getHostAnchor();
        }
    }

    @Test
    void ownEntityUsesActualEntityWorldAndPositionNotContextWorld() {
        ContainerContext ctx = mock(ContainerContext.class);
        ServerLevel actual = mock(ServerLevel.class);
        Entity entity = mock(Entity.class);
        Vec3 pos = new Vec3(3.25, 65.75, -9.5);
        when(entity.level()).thenReturn(actual);
        when(entity.position()).thenReturn(pos);
        when(ctx.getAnchor()).thenReturn(new ContainerAnchor.AtEntity(entity));
        when(ctx.getLevel()).thenReturn(mock(ServerLevel.class));

        var origin = PortalPositionResolver.resolve(ctx).orElseThrow();
        assertSame(actual, origin.level());
        assertEquals(pos, origin.pos());
        verify(ctx, never()).getHostAnchor();
    }

    @Test
    void borrowedPlayerRequiresPortalSwitchAndUsesRealOwnerPosition() {
        ContainerContext ctx = mock(ContainerContext.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.level()).thenReturn(level);
        when(player.position()).thenReturn(new Vec3(20.25, 70, 19.75));
        when(ctx.getHostAnchor()).thenReturn(new ContainerAnchor.AtPlayer(player));
        try (MockedStatic<GameplayConfig> config = mockStatic(GameplayConfig.class)) {
            assertTrue(PortalPositionResolver.resolve(ctx).isEmpty());
            verify(ctx, never()).getHostAnchor();
            config.when(GameplayConfig::portalUsesHostAnchor).thenReturn(true);
            var origin = PortalPositionResolver.resolve(ctx).orElseThrow();
            assertSame(level, origin.level());
            assertEquals(player.position(), origin.pos());
        }
    }

    @Test
    void invalidOwnAnchorDoesNotFallBackToHostOrViewer() {
        ContainerContext ctx = mock(ContainerContext.class);
        when(ctx.getAnchor()).thenReturn(new ContainerAnchor.AtBlock(BlockPos.ZERO));
        when(ctx.getLevel()).thenReturn(mock(Level.class));
        try (MockedStatic<GameplayConfig> config = mockStatic(GameplayConfig.class)) {
            config.when(GameplayConfig::portalUsesHostAnchor).thenReturn(true);
            assertTrue(PortalPositionResolver.resolve(ctx).isEmpty());
            verify(ctx, never()).getHostAnchor();
            verify(ctx, never()).getOwnerPlayer();
        }
    }

    @Test
    void removedEntityAndNonFiniteOrOutOfBoundsCoordinatesAreRejected() {
        ContainerContext ctx = mock(ContainerContext.class);
        Entity entity = mock(Entity.class);
        when(ctx.getAnchor()).thenReturn(new ContainerAnchor.AtEntity(entity));
        when(entity.level()).thenReturn(mock(ServerLevel.class));
        when(entity.position()).thenReturn(Vec3.ZERO);
        when(entity.isRemoved()).thenReturn(true);
        assertTrue(PortalPositionResolver.resolve(ctx).isEmpty());
        when(entity.isRemoved()).thenReturn(false);
        for (Vec3 bad : new Vec3[]{new Vec3(Double.NaN, 0, 0), new Vec3(0, Double.POSITIVE_INFINITY, 0),
                new Vec3(30_000_000, 0, 0), new Vec3(-30_000_001, 0, 0), new Vec3(0, 20_000_000, 0)}) {
            when(entity.position()).thenReturn(bad);
            assertTrue(PortalPositionResolver.resolve(ctx).isEmpty(), bad.toString());
        }
    }
}
