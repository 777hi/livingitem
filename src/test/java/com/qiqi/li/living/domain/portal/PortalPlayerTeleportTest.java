package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

/** 玩家过门的公开出口计算、前置权限与一次真实旅行事件取消守卫。 */
class PortalPlayerTeleportTest {

    private static final class Fixture {
        final ServerLevel source = mock(ServerLevel.class);
        final ServerLevel target = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final PortalForcer forcer = mock(PortalForcer.class);
        final WorldBorder border = new WorldBorder();
        final BlockPos corner = new BlockPos(10, 70, 20);
        final PortalPositionResolver.Origin origin = new PortalPositionResolver.Origin(source,
            new Vec3(800.5, 65.5, -1600.5));

        Fixture() {
            when(source.dimension()).thenReturn(Level.OVERWORLD);
            when(target.dimension()).thenReturn(Level.NETHER);
            when(source.getServer()).thenReturn(server);
            when(server.getLevel(Level.NETHER)).thenReturn(target);
            when(server.isLevelEnabled(target)).thenReturn(true);
            when(player.serverLevel()).thenReturn(source);
            when(player.canUsePortal(false)).thenReturn(true);
            when(player.canChangeDimensions(source, target)).thenReturn(true);
            when(player.getPose()).thenReturn(Pose.STANDING);
            when(player.getDimensions(Pose.STANDING)).thenReturn(EntityDimensions.scalable(0.6F, 1.8F));
            when(player.getDeltaMovement()).thenReturn(new Vec3(1, 2, 3));
            when(player.getYRot()).thenReturn(15.0F);
            when(player.getXRot()).thenReturn(10.0F);
            when(player.changeDimension(any())).thenReturn(player);
            DimensionType sourceType = mock(DimensionType.class);
            DimensionType targetType = mock(DimensionType.class);
            when(sourceType.coordinateScale()).thenReturn(1.0);
            when(targetType.coordinateScale()).thenReturn(8.0);
            when(source.dimensionType()).thenReturn(sourceType);
            when(target.dimensionType()).thenReturn(targetType);
            when(target.getWorldBorder()).thenReturn(border);
            when(target.getPortalForcer()).thenReturn(forcer);
            when(forcer.findClosestPortalPosition(any(), eq(true), same(border))).thenReturn(Optional.empty());
            when(forcer.createPortal(any(), eq(Direction.Axis.X))).thenReturn(Optional.of(new BlockUtil.FoundRectangle(corner, 2, 3)));
            when(target.getBlockState(any())).thenReturn(Blocks.NETHER_PORTAL.defaultBlockState());
            when(target.findFreePosition(any(), any(), any(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        }

        DimensionTransition transition() {
            ArgumentCaptor<DimensionTransition> captor = ArgumentCaptor.forClass(DimensionTransition.class);
            verify(player, times(1)).changeDimension(captor.capture());
            return captor.getValue();
        }
    }

    @Test
    void usesOriginScaleAndTargetBorderNotPlayerPosition() {
        Fixture f = new Fixture();
        f.border.setSize(100);
        when(f.player.position()).thenReturn(new Vec3(-3, 100, 7));

        assertEquals(PortalPlayerTeleport.Result.SUCCESS, PortalPlayerTeleport.teleport(f.player, f.origin));

        BlockPos expected = f.border.clampToBounds(800.5 / 8, 65.5, -1600.5 / 8);
        verify(f.forcer).findClosestPortalPosition(expected, true, f.border);
        verify(f.forcer).createPortal(expected, Direction.Axis.X);
        DimensionTransition transition = f.transition();
        assertSame(f.target, transition.newLevel());
        assertEquals(new Vec3(11, 70, 20.5), transition.pos());
        assertEquals(new Vec3(1, 2, 3), transition.speed());
        assertEquals(15, transition.yRot());
        assertEquals(10, transition.xRot());
        assertNotSame(DimensionTransition.DO_NOTHING, transition.postDimensionTransition());
        InOrder order = inOrder(f.player);
        order.verify(f.player).setPortalCooldown();
        order.verify(f.player).closeContainer();
        order.verify(f.player).changeDimension(any());
        verify(f.player, never()).setAsInsidePortal(any(), any());
    }

    @Test
    void zAxisExitRotatesSpeedAndYawAndUsesCollisionFreePosition() {
        Fixture f = new Fixture();
        when(f.target.getBlockState(any())).thenReturn(Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(NetherPortalBlock.AXIS, Direction.Axis.Z));
        Vec3 free = new Vec3(40, 80, 60);
        when(f.target.findFreePosition(any(), any(), any(), anyDouble(), anyDouble(), anyDouble()))
            .thenAnswer(invocation -> Optional.of(free.add(0, (double) 1.8F / 2, 0)));

        assertEquals(PortalPlayerTeleport.Result.SUCCESS, PortalPlayerTeleport.teleport(f.player, f.origin));
        DimensionTransition transition = f.transition();
        assertEquals(free, transition.pos());
        assertEquals(new Vec3(3, 2, -1), transition.speed());
        assertEquals(105, transition.yRot());
        assertEquals(10, transition.xRot());
    }

    @Test
    void existingPortalUsesLargestTargetRectangleAndDoesNotCreate() {
        Fixture f = new Fixture();
        BlockPos interior = f.corner.above();
        when(f.forcer.findClosestPortalPosition(any(), eq(true), same(f.border))).thenReturn(Optional.of(interior));
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState();
        when(f.target.getBlockState(any())).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            return pos.getX() >= 10 && pos.getX() <= 11 && pos.getY() >= 70 && pos.getY() <= 72 && pos.getZ() == 20
                ? portal : Blocks.AIR.defaultBlockState();
        });

        assertEquals(PortalPlayerTeleport.Result.SUCCESS, PortalPlayerTeleport.teleport(f.player, f.origin));
        assertEquals(new Vec3(11, 70, 20.5), f.transition().pos());
        verify(f.forcer, never()).createPortal(any(), any());
    }

    @Test
    void netherSourceUsesOverworldDestinationAndSearchRadiusFlag() {
        Fixture f = new Fixture();
        when(f.source.dimension()).thenReturn(Level.NETHER);
        when(f.target.dimension()).thenReturn(Level.OVERWORLD);
        when(f.server.getLevel(Level.OVERWORLD)).thenReturn(f.target);
        when(f.source.dimensionType().coordinateScale()).thenReturn(8.0);
        when(f.target.dimensionType().coordinateScale()).thenReturn(1.0);
        when(f.forcer.findClosestPortalPosition(any(), eq(false), same(f.border))).thenReturn(Optional.empty());

        assertEquals(PortalPlayerTeleport.Result.SUCCESS, PortalPlayerTeleport.teleport(f.player, f.origin));
        verify(f.forcer).findClosestPortalPosition(f.border.clampToBounds(800.5 * 8, 65.5, -1600.5 * 8), false, f.border);
    }

    @Test
    void portalPermissionAndCooldownRejectBeforeExitWorkAndDoNotCloseMenu() {
        Fixture f = new Fixture();
        when(f.player.canUsePortal(false)).thenReturn(false);
        assertEquals(PortalPlayerTeleport.Result.PORTAL_NOT_ALLOWED, PortalPlayerTeleport.teleport(f.player, f.origin));
        when(f.player.canUsePortal(false)).thenReturn(true);
        when(f.player.isOnPortalCooldown()).thenReturn(true);
        assertEquals(PortalPlayerTeleport.Result.PORTAL_COOLDOWN, PortalPlayerTeleport.teleport(f.player, f.origin));
        verifyNoInteractions(f.forcer);
        verify(f.player, never()).setPortalCooldown();
        verify(f.player, never()).closeContainer();
        verify(f.player, never()).changeDimension(any());
    }

    @Test
    void missingDisabledAndDimensionPermissionTargetsRejectWithoutSideEffects() {
        Fixture f = new Fixture();
        when(f.server.getLevel(Level.NETHER)).thenReturn(null);
        assertEquals(PortalPlayerTeleport.Result.TARGET_MISSING, PortalPlayerTeleport.teleport(f.player, f.origin));
        when(f.server.getLevel(Level.NETHER)).thenReturn(f.target);
        when(f.server.isLevelEnabled(f.target)).thenReturn(false);
        assertEquals(PortalPlayerTeleport.Result.TARGET_DISABLED, PortalPlayerTeleport.teleport(f.player, f.origin));
        when(f.server.isLevelEnabled(f.target)).thenReturn(true);
        when(f.player.canChangeDimensions(f.source, f.target)).thenReturn(false);
        assertEquals(PortalPlayerTeleport.Result.DIMENSION_CHANGE_NOT_ALLOWED, PortalPlayerTeleport.teleport(f.player, f.origin));
        verifyNoInteractions(f.forcer);
        verify(f.player, never()).setPortalCooldown();
        verify(f.player, never()).closeContainer();
        verify(f.player, never()).changeDimension(any());
    }

    @Test
    void unavailableExitConsumesAttemptCooldownButKeepsMenuAndDoesNotTransition() {
        Fixture f = new Fixture();
        when(f.forcer.createPortal(any(), any())).thenReturn(Optional.empty());
        assertEquals(PortalPlayerTeleport.Result.EXIT_UNAVAILABLE, PortalPlayerTeleport.teleport(f.player, f.origin));
        verify(f.player, times(1)).setPortalCooldown();
        verify(f.player, never()).closeContainer();
        verify(f.player, never()).changeDimension(any());
        verify(f.forcer, times(1)).createPortal(any(), eq(Direction.Axis.X));
    }

    public static final class CancelTravel {
        final Entity player;
        int events;
        CancelTravel(Entity player) { this.player = player; }
        @SubscribeEvent
        public void onTravel(EntityTravelToDimensionEvent event) {
            if (event.getEntity() == player) {
                events++;
                event.setCanceled(true);
            }
        }
    }

    @Test
    void canceledRealPlayerChangeDimensionFiresTravelEventExactlyOnce() {
        Fixture f = new Fixture();
        doCallRealMethod().when(f.player).changeDimension(any());
        CancelTravel cancel = new CancelTravel(f.player);
        NeoForge.EVENT_BUS.register(cancel);
        try {
            assertEquals(PortalPlayerTeleport.Result.TRANSITION_REJECTED, PortalPlayerTeleport.teleport(f.player, f.origin));
            assertEquals(1, cancel.events, "不在预检手工派发旅行事件");
            verify(f.player, times(1)).changeDimension(any());
            verify(f.player, times(1)).setPortalCooldown();
            verify(f.player, times(1)).closeContainer();
            verify(f.player, never()).setServerLevel(any());
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
        }
    }

    @Test
    void unsupportedDimensionInvalidOriginAndForeignPlayerLevelDoNothing() {
        Fixture f = new Fixture();
        assertEquals(PortalPlayerTeleport.Result.INVALID_ORIGIN, PortalPlayerTeleport.teleport(f.player,
            new PortalPositionResolver.Origin(f.source, new Vec3(Double.NaN, 64, 0))));
        when(f.source.dimension()).thenReturn(Level.END);
        assertEquals(PortalPlayerTeleport.Result.UNSUPPORTED_DIMENSION, PortalPlayerTeleport.teleport(f.player, f.origin));
        when(f.source.dimension()).thenReturn(Level.OVERWORLD);
        when(f.player.serverLevel()).thenReturn(mock(ServerLevel.class));
        assertEquals(PortalPlayerTeleport.Result.SOURCE_LEVEL_MISMATCH, PortalPlayerTeleport.teleport(f.player, f.origin));
        verifyNoInteractions(f.forcer);
        verify(f.player, never()).setPortalCooldown();
        verify(f.player, never()).closeContainer();
        verify(f.player, never()).changeDimension(any());
    }
}
