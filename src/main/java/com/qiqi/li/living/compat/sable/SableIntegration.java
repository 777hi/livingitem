package com.qiqi.li.living.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;
import java.util.Set;

public class SableIntegration {

    /**
     * 判定绳索是否与载具相连的边界盒膨胀量（方块）。
     *
     * <p>Sable 未暴露 RopePhysicsObject 的 attachment 目标（startAttachmentSubLevel 为 protected），
     * 因此改用几何相交作为保守判据：绳索包围盒与载具包围盒相交即视为存在软连接。
     * 膨胀 1 格容忍绳索端点恰好贴在载具表面而包围盒无重叠的边界情况。
     */
    private static final double ROPE_PROXIMITY_MARGIN = 1.0;

    static boolean isPlayerOnSubLevel(ServerPlayer player) {
        SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(subLevel instanceof ServerSubLevel)) return false;
        if (player.getVehicle() != null) return true;
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
        return false;
    }

    /**
     * 检测玩家所在载具是否存在软连接（绳索 / 关节等跨 SubLevel 连接）。
     *
     * <p>传送只移动玩家所在的单个刚体（{@link #teleportSubLevel}），而软连接的另一端
     * 不会跟随移动，物理约束会在下一帧被拉出巨大冲量，导致绳索弹飞、结构解体或卡死。
     * 因此检测到软连接时直接拒绝传送。
     *
     * <p>两类连接分别判定：
     * <ul>
     *   <li><b>绳索</b>：遍历物理系统的 ArbitraryPhysicsObject，取 RopePhysicsObject 的
     *       包围盒与载具包围盒做相交测试</li>
     *   <li><b>关节 / 方块实体连接</b>：{@link SubLevelHelper#getConnectedChain} 返回的
     *       连接链长度 &gt; 1 说明该载具与其他 SubLevel 被视为一体</li>
     * </ul>
     */
    static boolean hasSoftConnection(ServerPlayer player) {
        SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return false;

        Collection<SubLevel> chain = SubLevelHelper.getConnectedChain(serverSubLevel);
        // 诊断日志：连接链长度 > 1 或绳子相交为触发点；全 0 说明 Aeronautics 未走这些 API，需换判据
        if (chain.size() > 1) {
            org.slf4j.LoggerFactory.getLogger("living_item.teleport").info(
                "[SoftConnection] Blocked by connected chain: size={}", chain.size());
            return true;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(player.serverLevel());
        if (container == null) return false;

        SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
        BoundingBox3dc subLevelBounds = serverSubLevel.boundingBox();
        BoundingBox3d ropeBounds = new BoundingBox3d();

        int ropeCount = 0;
        for (ArbitraryPhysicsObject object : physicsSystem.getArbitraryObjects()) {
            if (!(object instanceof RopePhysicsObject rope)) continue;
            ropeCount++;

            rope.getBoundingBox(ropeBounds);
            if (intersects(ropeBounds, subLevelBounds, ROPE_PROXIMITY_MARGIN)) {
                org.slf4j.LoggerFactory.getLogger("living_item.teleport").info(
                    "[SoftConnection] Blocked by rope intersection: ropes={} margin={}",
                    ropeCount, ROPE_PROXIMITY_MARGIN);
                return true;
            }
        }

        if (ropeCount > 0) {
            org.slf4j.LoggerFactory.getLogger("living_item.teleport").debug(
                "[SoftConnection] Passed: {} rope(s) present but none intersecting vehicle", ropeCount);
        }

        return false;
    }

    private static boolean intersects(BoundingBox3dc a, BoundingBox3dc b, double margin) {
        return a.minX() - margin <= b.maxX() && a.maxX() + margin >= b.minX()
                && a.minY() - margin <= b.maxY() && a.maxY() + margin >= b.minY()
                && a.minZ() - margin <= b.maxZ() && a.maxZ() + margin >= b.minZ();
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