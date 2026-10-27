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

    /**
     * 机械动力坐垫的乘坐实体 —— 生物坐上 SeatBlock 时生成的隐形实体，骑乘关系的载具端。
     * 字符串判型：Sable 集成需在无 Create 环境可加载，不引 Create 的类依赖。
     */
    private static boolean isCreateSeat(Entity entity) {
        return entity.getClass().getName()
            .equals("com.simibubi.create.content.contraptions.actors.seat.SeatEntity");
    }

    static boolean teleportSubLevel(ServerPlayer player, double destX, double destY, double destZ) {
        SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return false;

        RigidBodyHandle handle = RigidBodyHandle.of(serverSubLevel);
        if (handle == null || !handle.isValid()) return false;

        ServerLevel level = player.serverLevel();
        int chunkX = (int) destX >> 4;
        int chunkZ = (int) destZ >> 4;
        // 3×3 预载：艇底贴地降落时目标点紧贴地形，地形碰撞体必须在传送瞬间就位
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }

        Pose3dc oldPose = serverSubLevel.logicalPose();
        Vector3dc oldAirshipPos = oldPose.position();

        // 传送前的世界投影包围盒（Sable 每 tick 按当前姿态更新）——艇上生物与艇底都在这个范围内
        BoundingBox3dc oldBounds = serverSubLevel.boundingBox();

        double offsetX = player.getX() - oldAirshipPos.x();
        double offsetZ = player.getZ() - oldAirshipPos.z();

        double newAirshipX = destX - offsetX;
        double newAirshipZ = destZ - offsetZ;

        // 艇底对齐目标 Y（A4，2026-10-28 恢复实施）。原「只升不降」是误诊回退：
        // 当时的失败症状（传送不了 / 飞艇变得很奇怪 / 方块破坏后复原）实为下方
        // lastNetworkedPose 压制杀死客户端位姿同步，并无「物理互穿 / 飞艇乱飞」
        // 的实测证据（用户从未报过该现象），见 docs/buffer/code-doc-alignment.md A4。
        double newAirshipY = oldAirshipPos.y() + (destY - oldBounds.minY());

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

        // 坐着的生物随艇（2026-10-28 用户定案）：「坐着」= 坐在机械动力坐垫上的生物。
        // 载着乘客的坐椅实体一起平移，乘客由骑乘系统放回座位；操作者本人走下方
        // playerLocal 落位（其所骑载具无论是不是坐垫都跟，否则会被拽回原地）。
        // ⚠️ 判据：坐垫实体 && 有乘客 && 包围盒与艇的世界投影包围盒相交 —— 贴近 plot 时
        //    Sable 每 tick 末强制清 tracking（「不能既 tracking 又在 plot 内」），「谁在艇上」
        //    只能按几何判定；坐垫必然载着乘客 ⇒ 有乘客的坐椅就是「坐着的生物」。
        //    站甲板的生物、空坐椅、其它载具（马 / 船 / 载具叠载具）不跟随。
        //    搬完把 tracking 指回本艇：网络化按 lastPose 反算成 plot 局部坐标、客户端
        //    plotLerpTo 跟随（与正常飞行同源），绕过客户端「world 坐标在 plotgrid 内但不在
        //    任何 plot ⇒ 丢弃位置包」分支。
        Vec3 teleportDelta = new Vec3(
            newPos.x() - oldAirshipPos.x(),
            newPos.y() - oldAirshipPos.y(),
            newPos.z() - oldAirshipPos.z());
        var region = new net.minecraft.world.phys.AABB(
            oldBounds.minX() - 2, oldBounds.minY() - 2, oldBounds.minZ() - 2,
            oldBounds.maxX() + 2, oldBounds.maxY() + 2, oldBounds.maxZ() + 2);
        var shipBox = new net.minecraft.world.phys.AABB(
            oldBounds.minX(), oldBounds.minY(), oldBounds.minZ(),
            oldBounds.maxX(), oldBounds.maxY(), oldBounds.maxZ());
        Entity root = player.getRootVehicle();
        for (Entity seat : level.getEntities(player, region,
                e -> e != root && e.isVehicle() && isCreateSeat(e)
                    && e.getBoundingBox().intersects(shipBox))) {
            seat.setPos(seat.getX() + teleportDelta.x,
                seat.getY() + teleportDelta.y,
                seat.getZ() + teleportDelta.z);
            ((EntityMovementExtension) seat).sable$setTrackingSubLevel(serverSubLevel);
        }
        if (root != null && root != player) {
            root.setPos(root.getX() + teleportDelta.x,
                root.getY() + teleportDelta.y,
                root.getZ() + teleportDelta.z);
            ((EntityMovementExtension) root).sable$setTrackingSubLevel(serverSubLevel);
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