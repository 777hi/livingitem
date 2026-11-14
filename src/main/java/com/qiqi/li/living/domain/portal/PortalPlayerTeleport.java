package com.qiqi.li.living.domain.portal;

import java.util.Optional;

import com.mojang.logging.LogUtils;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** 即时玩家过门；使用真实来源点和公开出口 API，旅行事件仅由 changeDimension 派发。 */
public final class PortalPlayerTeleport {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Result {
        SUCCESS,
        INVALID_ORIGIN,
        UNSUPPORTED_DIMENSION,
        SOURCE_LEVEL_MISMATCH,
        PORTAL_NOT_ALLOWED,
        PORTAL_COOLDOWN,
        TARGET_MISSING,
        TARGET_DISABLED,
        DIMENSION_CHANGE_NOT_ALLOWED,
        EXIT_UNAVAILABLE,
        TRANSITION_REJECTED,
        TRANSITION_FAILED
    }

    private PortalPlayerTeleport() {}

    public static Result teleport(ServerPlayer player, PortalPositionResolver.Origin origin) {
        if (!PortalPositionResolver.isUsable(origin)) return Result.INVALID_ORIGIN;
        ServerLevel source = origin.level();
        ResourceKey<Level> destination;
        if (source.dimension() == Level.OVERWORLD) {
            destination = Level.NETHER;
        } else if (source.dimension() == Level.NETHER) {
            destination = Level.OVERWORLD;
        } else {
            return Result.UNSUPPORTED_DIMENSION;
        }
        if (player.serverLevel() != source) return Result.SOURCE_LEVEL_MISMATCH;
        if (!player.canUsePortal(false)) return Result.PORTAL_NOT_ALLOWED;
        if (player.isOnPortalCooldown()) return Result.PORTAL_COOLDOWN;
        MinecraftServer server = source.getServer();
        ServerLevel target = server == null ? null : server.getLevel(destination);
        if (target == null) return Result.TARGET_MISSING;
        if (!server.isLevelEnabled(target)) return Result.TARGET_DISABLED;
        if (!player.canChangeDimensions(source, target)) return Result.DIMENSION_CHANGE_NOT_ALLOWED;

        player.setPortalCooldown();
        try {
            DimensionTransition transition = findTransition(player, origin, target);
            if (transition == null) return Result.EXIT_UNAVAILABLE;
            player.closeContainer();
            return player.changeDimension(transition) != null ? Result.SUCCESS : Result.TRANSITION_REJECTED;
        } catch (RuntimeException e) {
            LOGGER.error("地狱门玩家传送异常：玩家 {}，来源 {}，目标 {}，不重试",
                player.getUUID(), origin.pos(), destination.location(), e);
            return Result.TRANSITION_FAILED;
        }
    }

    private static DimensionTransition findTransition(ServerPlayer player, PortalPositionResolver.Origin origin,
                                                       ServerLevel target) {
        double scale = DimensionType.getTeleportationScale(origin.level().dimensionType(), target.dimensionType());
        WorldBorder border = target.getWorldBorder();
        Vec3 pos = origin.pos();
        BlockPos searchPos = border.clampToBounds(pos.x * scale, pos.y, pos.z * scale);
        Optional<BlockPos> found = target.getPortalForcer().findClosestPortalPosition(
            searchPos, target.dimension() == Level.NETHER, border);
        BlockUtil.FoundRectangle rectangle;
        DimensionTransition.PostDimensionTransition post;
        if (found.isPresent()) {
            BlockPos portalPos = found.get();
            BlockState state = target.getBlockState(portalPos);
            rectangle = BlockUtil.getLargestRectangleAround(portalPos,
                state.getValue(BlockStateProperties.HORIZONTAL_AXIS), 21, Direction.Axis.Y, 21,
                candidate -> target.getBlockState(candidate) == state);
            post = DimensionTransition.PLAY_PORTAL_SOUND.then(entity -> entity.placePortalTicket(portalPos));
        } else {
            Optional<BlockUtil.FoundRectangle> created = target.getPortalForcer().createPortal(searchPos, Direction.Axis.X);
            if (created.isEmpty()) return null;
            rectangle = created.get();
            post = DimensionTransition.PLAY_PORTAL_SOUND.then(DimensionTransition.PLACE_PORTAL_TICKET);
        }
        return exitTransition(player, target, rectangle, post);
    }

    /** 虚拟源无世界门几何，固定原版默认 X 轴及相对偏移 (0.5, 0, 0)。 */
    private static DimensionTransition exitTransition(ServerPlayer player, ServerLevel target,
                                                       BlockUtil.FoundRectangle rectangle,
                                                       DimensionTransition.PostDimensionTransition post) {
        BlockPos corner = rectangle.minCorner;
        Direction.Axis exitAxis = target.getBlockState(corner)
            .getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS).orElse(Direction.Axis.X);
        EntityDimensions dimensions = player.getDimensions(player.getPose());
        double lateral = (double) dimensions.width() / 2.0
            + ((double) rectangle.axis1Size - (double) dimensions.width()) * 0.5;
        boolean xAxis = exitAxis == Direction.Axis.X;
        Vec3 exit = new Vec3(corner.getX() + (xAxis ? lateral : 0.5), corner.getY(),
            corner.getZ() + (xAxis ? 0.5 : lateral));
        Vec3 free = PortalShape.findCollisionFreePosition(exit, target, player, dimensions);
        Vec3 speed = player.getDeltaMovement();
        boolean rotated = exitAxis != Direction.Axis.X;
        Vec3 exitSpeed = rotated ? new Vec3(speed.z, speed.y, -speed.x) : speed;
        return new DimensionTransition(target, free, exitSpeed, player.getYRot() + (rotated ? 90.0F : 0.0F),
            player.getXRot(), post);
    }
}
