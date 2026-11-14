package com.qiqi.li.living.domain.portal;

import java.util.Optional;

import com.qiqi.li.living.config.GameplayConfig;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 门的真实来源世界与位置；自身锚点优先，借位仅受 portal 配置控制。 */
public final class PortalPositionResolver {

    public record Origin(ServerLevel level, Vec3 pos) {}

    private PortalPositionResolver() {}

    public static Optional<Origin> resolve(ContainerContext ctx) {
        ContainerAnchor anchor = ctx.getAnchor();
        if (anchor == null && GameplayConfig.portalUsesHostAnchor()) {
            anchor = ctx.getHostAnchor();
        }
        Origin origin = switch (anchor) {
            case ContainerAnchor.AtBlock block -> ctx.getLevel() instanceof ServerLevel level
                && block.pos() != null ? new Origin(level, Vec3.atCenterOf(block.pos())) : null;
            case ContainerAnchor.AtPlayer player -> ofEntity(player.player());
            case ContainerAnchor.AtEntity entity -> ofEntity(entity.entity());
            case null -> null;
        };
        return isUsable(origin) ? Optional.of(origin) : Optional.empty();
    }

    private static Origin ofEntity(Entity entity) {
        return entity != null && !entity.isRemoved() && entity.level() instanceof ServerLevel level
            ? new Origin(level, entity.position()) : null;
    }

    static boolean isUsable(Origin origin) {
        if (origin == null || origin.level() == null || origin.pos() == null) return false;
        Vec3 pos = origin.pos();
        return Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)
            && Level.isInSpawnableBounds(BlockPos.containing(pos));
    }
}
