package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.compat.sable.ModSable;
import com.qiqi.li.living.function.LivingEnderPearlFunction;
import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.logging.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.DimensionTransition;

import java.util.ArrayList;
import java.util.List;

/**
 * 传送辅助类 - 处理末影珍珠传送的核心逻辑
 * 支持地图位置传送、旗帜传送、跨维度传送以及子位面传送
 */
public final class TeleportHelper {

    /** 传送冷却时间（tick），40 tick = 2秒 */
    public static final int COOLDOWN_TICKS = 40;
    /** 传送后造成的摔落伤害值 */
    public static final float FALL_DAMAGE = 5.0f;

    private TeleportHelper() {}

    /**
     * 传送到地图上的指定坐标位置
     * @param player 目标玩家
     * @param sourceLevel 源维度
     * @param targetLevel 目标维度
     * @param worldX 目标世界X坐标
     * @param worldZ 目标世界Z坐标
     * @param pearlStack 使用的末影珍珠物品堆
     * @param unexplored 是否为未探索传送（消耗不同数量的珍珠）
     * @return 传送是否成功
     */
    public static boolean teleportToMapPosition(ServerPlayer player, ServerLevel sourceLevel,
                                                 ServerLevel targetLevel,
                                                 double worldX, double worldZ,
                                                 ItemStack pearlStack, boolean unexplored) {
        if (LivingEnderPearlFunction.isOnCooldown(player)) return false;

        long totalStart = System.nanoTime();
        long chunkLoadMs = ensureChunkLoaded(targetLevel, worldX, worldZ);

        BlockPos targetPos = BlockPos.containing(worldX, player.getY(), worldZ);
        int safeY = findSafeY(targetLevel, targetPos);
        if (safeY < targetLevel.getMinBuildHeight()) return false;

        double destX = worldX + 0.5;
        double destY = safeY + 1.0;
        double destZ = worldZ + 0.5;

        boolean result = executeTeleport(player, sourceLevel, targetLevel, destX, destY, destZ, pearlStack, unexplored);

        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        if (result) {
            PerfMetrics.recordTeleport(chunkLoadMs, totalMs);
        }
        return result;
    }

    /**
     * 传送到旗帜位置
     * @param player 目标玩家
     * @param sourceLevel 源维度
     * @param targetLevel 目标维度
     * @param bannerPos 旗帜所在方块位置
     * @param pearlStack 使用的末影珍珠物品堆
     * @param unexplored 是否为未探索传送
     * @return 传送是否成功
     */
    public static boolean teleportToBanner(ServerPlayer player, ServerLevel sourceLevel,
                                            ServerLevel targetLevel,
                                            BlockPos bannerPos,
                                            ItemStack pearlStack, boolean unexplored) {
        if (LivingEnderPearlFunction.isOnCooldown(player)) return false;

        long totalStart = System.nanoTime();
        long chunkLoadMs = ensureChunkLoaded(targetLevel, bannerPos.getX(), bannerPos.getZ());

        double destX = bannerPos.getX() + 0.5;
        double destY = bannerPos.getY() + 1.0;
        double destZ = bannerPos.getZ() + 0.5;

        boolean result = executeTeleport(player, sourceLevel, targetLevel, destX, destY, destZ, pearlStack, unexplored);

        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        if (result) {
            PerfMetrics.recordTeleport(chunkLoadMs, totalMs);
        }
        return result;
    }

    /**
     * 执行实际传送操作的核心方法
     * 处理同维度/跨维度传送、骑行实体传送、子位面传送等复杂情况
     */
    private static boolean executeTeleport(ServerPlayer player, ServerLevel sourceLevel,
                                            ServerLevel targetLevel,
                                            double destX, double destY, double destZ,
                                            ItemStack pearlStack, boolean unexplored) {
        // 判断是否为跨维度传送
        boolean crossDim = player.level().dimension() != targetLevel.dimension();

        ModLog.TELEPORT.info("Teleport start: player={} dim={} dest=({},{},{}) crossDim={} unexplored={}",
            player.getName().getString(), targetLevel.dimension().location(), destX, destY, destZ, crossDim, unexplored);

        if (ModSable.isPlayerOnSubLevel(player)) {
            // 玩家在子位面中，不支持跨维度传送
            if (crossDim) {
                sendInsufficientAuthorityMessage(player);
                return false;
            }
            ensureChunkLoaded(targetLevel, destX, destZ);
            // 使用子位面专用传送
            if (!ModSable.teleportSubLevel(player, destX, destY, destZ)) {
                return false;
            }
        } else {
            // 获取玩家当前骑乘的实体（如有）
            Entity vehicle = player.getVehicle();
            if (crossDim) {
                // === 跨维度传送 ===
                DimensionTransition transition = new DimensionTransition(
                    targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
                    player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
                if (vehicle != null) {
                    // 有坐骑时先传送坐骑
                    vehicle.changeDimension(transition);
                } else {
                    player.changeDimension(transition);
                }
            } else {
                // === 同维度传送 ===
                if (vehicle != null) {
                    // 有坐骑时：先让所有乘客下车，传送坐骑，再传送玩家，最后让乘客重新上车
                    List<Entity> passengers = new ArrayList<>(vehicle.getPassengers());
                    for (Entity passenger : passengers) {
                        passenger.stopRiding();
                    }
                    // 先传送坐骑到目标位置
                    vehicle.teleportTo(destX, destY, destZ);
                    // 然后传送玩家（使用DimensionTransition保持朝向）
                    DimensionTransition transition = new DimensionTransition(
                        targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
                        player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
                    player.changeDimension(transition);
                    // 将其他乘客（非玩家）也传送到目标位置
                    for (Entity passenger : passengers) {
                        if (passenger != player && passenger instanceof ServerPlayer serverPassenger) {
                            serverPassenger.connection.teleport(destX, destY, destZ, serverPassenger.getYRot(), serverPassenger.getXRot());
                            serverPassenger.connection.resetPosition();
                        }
                    }
                    // 让所有乘客重新骑上坐骑
                    for (Entity passenger : passengers) {
                        passenger.startRiding(vehicle, true);
                    }
                } else {
                    DimensionTransition transition = new DimensionTransition(
                        targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
                        player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
                    player.changeDimension(transition);
                }
            }
        }
        // 重置摔落距离，避免传送后误判摔落伤害
        player.resetFallDistance();

        // 在目标位置生成传送门粒子效果
        targetLevel.sendParticles(ParticleTypes.PORTAL,
            destX, destY + 1, destZ,
            32, 0.5, 1.0, 0.5, 0.5);

        // 跨维度时在源位置也生成粒子效果
        if (crossDim) {
            sourceLevel.sendParticles(ParticleTypes.PORTAL,
                player.getX(), player.getY() + 1, player.getZ(),
                32, 0.5, 1.0, 0.5, 0.5);
        }

        // 播放末影人传送音效
        targetLevel.playSound(null, destX, destY, destZ,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.0f);

        // 施加固定摔落伤害作为传送代价
        player.hurt(player.damageSources().fall(), FALL_DAMAGE);

        // 消耗珍珠并设置冷却时间
        consumePearl(player, pearlStack, unexplored);
        LivingEnderPearlFunction.setCooldown(player, COOLDOWN_TICKS);

        ModLog.TELEPORT.info("Teleport complete: player={} dim={} dest=({},{},{})",
            player.getName().getString(), targetLevel.dimension().location(), destX, destY, destZ);

        return true;
    }

    /**
     * 消耗末影珍珠
     * 创造模式不消耗；未探索传送从背包中消耗指定数量；普通传送消耗手持的1个珍珠
     */
    private static void consumePearl(ServerPlayer player, ItemStack pearlStack, boolean unexplored) {
        if (player.isCreative()) return;
        if (unexplored) {
            // 未探索传送：从背包中消耗指定数量珍珠
            LivingEnderPearlFunction.consumeFromInventory(player, MapTeleportExecutor.UNEXPLORED_PEARL_COST);
        } else {
            // 普通传送：消耗1个手持珍珠
            pearlStack.shrink(1);
        }
    }

    /**
     * 确保目标区块已加载
     * @return 区块加载耗时（毫秒）
     */
    private static long ensureChunkLoaded(ServerLevel level, double x, double z) {
        int chunkX = (int) x >> 4;
        int chunkZ = (int) z >> 4;
        ModLog.TELEPORT.debug("Loading chunk: dim={} pos=({},{})", level.dimension().location(), chunkX, chunkZ);
        long start = System.nanoTime();
        level.getChunk(chunkX, chunkZ);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (elapsed > 50) {
            ModLog.TELEPORT.warn("Slow chunk load: dim={} pos=({},{}) elapsed={}ms",
                level.dimension().location(), chunkX, chunkZ, elapsed);
        }
        return elapsed;
    }

    /**
     * 寻找安全的地面Y坐标（使用运动阻挡高度图）
     * @return 安全的地面Y坐标
     */
    private static int findSafeY(ServerLevel level, BlockPos pos) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY();
    }

    /**
     * 发送旗帜传送成功消息
     */
    public static void sendBannerTeleportMessage(ServerPlayer player, Component bannerName) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.teleported_to_banner", bannerName),
            true);
    }

    /**
     * 发送目标点传送成功消息
     */
    public static void sendTargetPointMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.teleported_to_target"),
            true);
    }

    /**
     * 发送未探索区域传送消息
     */
    public static void sendUnexploredMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.unexplored"),
            true);
    }

    /**
     * 发送跨维度传送消息
     */
    public static void sendCrossDimensionMessage(ServerPlayer player, ResourceKey<Level> dimension) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.cross_dimension",
                Component.translatable("dimension." + dimension.location().getNamespace() + "." + dimension.location().getPath())),
            true);
    }

    /**
     * 发送权限不足消息（子位面中无法跨维度传送时显示）
     */
    public static void sendInsufficientAuthorityMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.insufficient_authority"),
            true);
    }
}