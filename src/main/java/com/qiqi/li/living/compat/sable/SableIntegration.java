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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;
import java.util.Set;

/**
 * Sable 飞艇 SubLevel 传送的具体实现。
 *
 * <p>处理玩家所在载具的 SubLevel 姿态变换与客户端位置同步，
 * 并在检测到跨 SubLevel 软连接（绳索 / 关节）时拒绝传送，避免物理约束被拉裂。
 * 仅在 Sable 加载且集成可用时由 {@link ModSable} 调用。</p>
 */
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
        // ⚠️ 纯查询（2026-10-27 审查 P2-3）：曾对站立玩家顺手清 tracking —— 而
        //    TeleportHelper 的拒绝路径（跨维度权能不足 / 软连接）也会调本方法 ⇒
        //    玩家没被传送却先被清了 tracking ⇒ entities_stick_sublevels 不再带动
        //    ⇒ 从飞艇脱离坠落。清理由 teleportSubLevel 在确认传送的时点负责。
        SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        return subLevel instanceof ServerSubLevel && player.getVehicle() != null;
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

        // 传送前的世界包围盒（Sable 每 tick 按当前姿态更新）——艇上生物都在这个范围内
        BoundingBox3dc oldBounds = serverSubLevel.boundingBox();

        double offsetX = player.getX() - oldAirshipPos.x();
        double offsetZ = player.getZ() - oldAirshipPos.z();

        double newAirshipX = destX - offsetX;
        double newAirshipZ = destZ - offsetZ;

        // 高度保持旧规则：只升不降（2026-10-28 实机回退）。
        // A4 曾改为「艇底对齐目标 Y」（允许下降）——真机实测失败：飞艇被拽到地表后
        // Rapier 物理与地形剧烈互穿，客户端 sublevel 姿态脱钩（方块破坏后复原、
        // passengers for unknown entity），见 docs/buffer/code-doc-alignment.md A4。
        // 「艇底贴地降落」需要与 Sable 物理专门集成（落地缓冲 / 区块预载），当前不做。
        double newAirshipY = Math.max(oldAirshipPos.y(), destY);

        Quaterniondc orientation = oldPose.orientation();
        Vector3dc newPos = new Vector3d(newAirshipX, newAirshipY, newAirshipZ);

        Vector3d playerLocal = oldPose.transformPositionInverse(
            new Vector3d(player.getX(), player.getY(), player.getZ()), new Vector3d());

        // 对齐 Sable 原生 /sable sub_level teleport：先归零刚体速度再 teleport，
        // 防止传送前的残余动量把飞艇在新位置继续拖走。
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            container.physicsSystem().getPipeline().resetVelocity(serverSubLevel);
        }
        serverSubLevel.logicalPose().position().set(newPos);
        serverSubLevel.logicalPose().orientation().set(orientation);
        handle.teleport(newPos, orientation);

        // ⚠️ 艇上所有生物一起传送（2026-10-28 用户定）：飞艇平移后，被 stick 系统携带的
        //    生物（站在甲板上的玩家 / 生物 / 载具）的 tracking 仍指向本飞艇，必须套用
        //    同一位移——否则 updateLastPose 归零差值后它们留在原地（空中则坠落）。
        //    被破坏半……不对，此处是飞艇：判定用「tracking == 本飞艇」，仅路过艇边的
        //    非携带实体不受影响。操作者本人走下方 playerLocal 精确落位，此处排除。
        Vec3 teleportDelta = new Vec3(
            newPos.x() - oldAirshipPos.x(),
            newPos.y() - oldAirshipPos.y(),
            newPos.z() - oldAirshipPos.z());
        var region = new net.minecraft.world.phys.AABB(
            oldBounds.minX() - 2, oldBounds.minY() - 2, oldBounds.minZ() - 2,
            oldBounds.maxX() + 2, oldBounds.maxY() + 2, oldBounds.maxZ() + 2);
        for (Entity carried : level.getEntities(player, region,
                e -> ((EntityMovementExtension) e).sable$getTrackingSubLevel() == serverSubLevel)) {
            carried.setPos(
                carried.getX() + teleportDelta.x,
                carried.getY() + teleportDelta.y,
                carried.getZ() + teleportDelta.z);
        }

        // ⚠️ 根载具显式补搬：坐椅等非生物实体可能不走 Sable 碰撞携带路径（无 tracking），
        //    留在原地则下一 tick 骑乘系统会把操作者拽回旧位置。已由循环搬走的
        //    （tracking == 本飞艇）与「在 plot 内被 Sable 自带」的跳过，避免二次位移。
        Entity root = player.getRootVehicle();
        if (root != null && root != player
                && ((EntityMovementExtension) root).sable$getTrackingSubLevel() != serverSubLevel
                && Sable.HELPER.getContaining(root) == null) {
            root.setPos(root.getX() + teleportDelta.x,
                root.getY() + teleportDelta.y,
                root.getZ() + teleportDelta.z);
        }

        Vector3d newPlayerPos = serverSubLevel.logicalPose().transformPosition(playerLocal, new Vector3d());
        player.setPos(newPlayerPos.x(), newPlayerPos.y(), newPlayerPos.z());

        serverSubLevel.updateLastPose();

        // ⚠️ 不碰 lastNetworkedPose / lastNetworkedStopped（2026-10-28 全面审查根因）：
        //    SubLevelTrackingSystem#sendMovementUpdates 只在「logicalPose 与 lastNetworkedPose
        //    有差」时才向客户端发 MOVE 快照——手动把 lastNetworkedPose 设成新位姿恰好骗过
        //    该判据，客户端永远收不到传送后的位姿，飞艇在客户端原地不动（服务端已到位），
        //    即「传送不了」的根因。交由 Sable 正常发 MOVE；两个速度清零仅防物理暂停时
        //    把传送前的残速发进快照导致客户端外插甩飞。
        serverSubLevel.latestLinearVelocity.zero();
        serverSubLevel.latestAngularVelocity.zero();

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