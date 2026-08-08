package com.qiqi.li.living.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Set;

public class SableIntegration {

    static boolean isPlayerOnSubLevel(ServerPlayer player) {
        SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(subLevel instanceof ServerSubLevel)) return false;
        if (player.getVehicle() != null) return true;
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
        return false;
    }

    static boolean teleportSubLevel(ServerPlayer player, double destX, double destY, double destZ) {
        SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return false;

        RigidBodyHandle handle = RigidBodyHandle.of(serverSubLevel);
        if (handle == null || !handle.isValid()) return false;

        ServerLevel level = player.serverLevel();
        int chunkX = (int) destX >> 4;
        int chunkZ = (int) destZ >> 4;
        level.getChunk(chunkX, chunkZ);

        Pose3dc oldPose = serverSubLevel.logicalPose();
        Vector3dc oldAirshipPos = oldPose.position();

        double offsetX = player.getX() - oldAirshipPos.x();
        double offsetZ = player.getZ() - oldAirshipPos.z();

        double newAirshipX = destX - offsetX;
        double newAirshipZ = destZ - offsetZ;

        double currentAirshipY = oldAirshipPos.y();
        double newAirshipY = Math.max(currentAirshipY, destY);

        Quaterniondc orientation = oldPose.orientation();
        Vector3dc newPos = new Vector3d(newAirshipX, newAirshipY, newAirshipZ);

        Vector3d playerLocal = oldPose.transformPositionInverse(
            new Vector3d(player.getX(), player.getY(), player.getZ()), new Vector3d());

        serverSubLevel.logicalPose().position().set(newPos);
        serverSubLevel.logicalPose().orientation().set(orientation);
        handle.teleport(newPos, orientation);

        Vector3d newPlayerPos = serverSubLevel.logicalPose().transformPosition(playerLocal, new Vector3d());
        player.setPos(newPlayerPos.x(), newPlayerPos.y(), newPlayerPos.z());

        serverSubLevel.updateLastPose();

        serverSubLevel.lastNetworkedPose().set(serverSubLevel.logicalPose());
        serverSubLevel.latestLinearVelocity.zero();
        serverSubLevel.latestAngularVelocity.zero();
        serverSubLevel.setLastNetworkedStopped(true);

        // 同步客户端位置：发送世界坐标（而非SubLevel局部坐标），因为传送后plot仍在旧位置，
        // getContaining(newPlayerPos)会返回null，玩家不在SubLevel内。
        // 若发送局部坐标，客户端会渲染玩家在0,0,0附近，而非飞艇旁，导致"只传送飞艇不传送玩家"。
        // SubLevelEntityCollision基于logicalPose处理碰撞，玩家可正常站在飞艇方块上。
        player.connection.send(new ClientboundPlayerPositionPacket(
            newPlayerPos.x, newPlayerPos.y, newPlayerPos.z,
            player.getYRot(), player.getXRot(),
            Set.of(), -1
        ));
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);

        return true;
    }

    private SableIntegration() {}
}