package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.compat.sable.ModSable;
import com.qiqi.li.living.domain.map.LivingEnderPearlFunction;
import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.logging.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.DimensionTransition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送辅助类 - 处理末影珍珠传送的核心逻辑
 * 支持地图位置传送、旗帜传送、跨维度传送以及子位面传送
 */
public final class TeleportHelper {

    /** 传送冷却时间（tick），40 tick = 2秒 */
    public static final int COOLDOWN_TICKS = 40;
    /** 传送后造成的摔落伤害值 */
    public static final float FALL_DAMAGE = 5.0f;

    /**
     * 传送期间绕过 NeoForge setPosRaw 中同步 getChunk 的标记。
     * 由 {@link com.qiqi.li.living.mixin.EntitySetPosRawMixin} 检测。
     */
    private static final ThreadLocal<Boolean> BYPASSING_CHUNK_LOAD = ThreadLocal.withInitial(() -> false);

    /**
     * 是否正在绕过区块加载（由 Mixin 调用）。
     */
    public static boolean isBypassingChunkLoad() {
        return BYPASSING_CHUNK_LOAD.get();
    }

    /** 未探索传送后临时降低视距的玩家状态，避免一次性加载大量区块 */
    private static final Map<UUID, ReducedViewState> REDUCED_VIEWS = new ConcurrentHashMap<>();
    /** 传送后临时视距值，仅加载玩家周围 5×5=25 个区块 */
    private static final int REDUCED_VIEW_DISTANCE = 2;
    /** 视距恢复延迟（毫秒），2秒后恢复原始视距 */
    private static final long VIEW_RESTORE_DELAY_MS = 2000;

    private record ReducedViewState(int originalViewDistance, long restoreTimeMs) {}

    /**
     * 获取玩家当前的临时降低视距值，若无需降低则返回 null。
     * 由 {@link com.qiqi.li.living.mixin.ChunkMapMixin} 调用。
     */
    public static Integer getReducedViewDistance(ServerPlayer player) {
        ReducedViewState state = REDUCED_VIEWS.get(player.getUUID());
        if (state == null) return null;
        if (System.currentTimeMillis() >= state.restoreTimeMs) {
            REDUCED_VIEWS.remove(player.getUUID());
            return null;
        }
        return REDUCED_VIEW_DISTANCE;
    }

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
        int safeY = getSafeYFromNoise(targetLevel, (int) worldX, (int) worldZ);
        if (safeY < targetLevel.getMinBuildHeight()) return false;

        prewarmTargetChunks(targetLevel, (int) worldX, (int) worldZ, player.getId());

        double destX = worldX + 0.5;
        double destY = safeY;
        double destZ = worldZ + 0.5;

        boolean result = executeTeleport(player, sourceLevel, targetLevel, destX, destY, destZ, pearlStack, unexplored);

        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        if (result) {
            PerfMetrics.recordTeleport(0, totalMs);
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

        double destX = bannerPos.getX() + 0.5;
        double destY = bannerPos.getY() + 1.0;
        double destZ = bannerPos.getZ() + 0.5;

        boolean result = executeTeleport(player, sourceLevel, targetLevel, destX, destY, destZ, pearlStack, unexplored);

        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        if (result) {
            PerfMetrics.recordTeleport(0, totalMs);
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
            ensureChunkForSubLevel(targetLevel, destX, destZ);
            // 使用子位面专用传送
            if (!ModSable.teleportSubLevel(player, destX, destY, destZ)) {
                return false;
            }
        } else {
            // 未探索传送时临时降低视距，仅加载玩家周围5×5=25个区块，避免一次性加载视距内全部区块导致空白等待
            if (unexplored && player.requestedViewDistance() > REDUCED_VIEW_DISTANCE) {
                REDUCED_VIEWS.put(player.getUUID(), new ReducedViewState(
                    player.requestedViewDistance(), System.currentTimeMillis() + VIEW_RESTORE_DELAY_MS));
            }
            // 获取玩家当前骑乘的实体（如有）
            Entity vehicle = player.getVehicle();
            BYPASSING_CHUNK_LOAD.set(true);
            try {
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
                        player.teleportTo(destX, destY, destZ);
                    }
                }
            } finally {
                BYPASSING_CHUNK_LOAD.set(false);
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
     * 从噪声直接计算目标位置的地面Y坐标（玩家脚底Y坐标），零区块加载。
     *
     * <p>使用 {@link ChunkGenerator#getBaseHeight} 通过 {@link Heightmap.Types#MOTION_BLOCKING}
     * 从密度函数噪声计算地表高度，无需加载任何区块。对于 {@link net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator}，
     * 其内部调用 {@code iterateNoiseColumn} 遍历噪声柱，在第一个不透明方块处停止并返回 Y+1。
     * 返回的 Y 即为玩家脚底应站的 Y（第一个空气方块）。</p>
     *
     * <p>精度说明：噪声高度不包含地表装饰（树木、结构等），在极端情况下与实际地形可能偏差 1-5 格。
     * 但偏差通常可接受，且可通过传送后的摔落/攀爬自动修正。</p>
     */
    private static int getSafeYFromNoise(ServerLevel level, int x, int z) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        return generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING, level, randomState);
    }

    /**
     * 异步预热目标区域区块，减少传送后服务端区块生成压力。
     *
     * <p>通过 {@code addRegionTicket} 添加高优先级区块加载标记（非阻塞），
     * 并通过工作线程调用 {@code getChunkFuture(LIGHT)} 触发世界生成线程池
     * 提前开始异步生成区块。传送本身不等待预热完成，区块在后台生成。</p>
     *
     * @param level 目标维度
     * @param worldX 目标世界 X 坐标
     * @param worldZ 目标世界 Z 坐标
     * @param playerId 玩家实体 ID，用于 ticket 标识
     */
    private static void prewarmTargetChunks(ServerLevel level, int worldX, int worldZ, int playerId) {
        int cx = worldX >> 4;
        int cz = worldZ >> 4;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.getChunkSource().addRegionTicket(
                    TicketType.POST_TELEPORT,
                    new ChunkPos(cx + dx, cz + dz),
                    2,
                    playerId);
            }
        }

        CompletableFuture.runAsync(() -> {
            level.getChunkSource().getChunkFuture(
                cx, cz, ChunkStatus.LIGHT, true);
        });
    }

    /**
     * 子位面传送时确保目标区块已加载（子位面传送不经过 changeDimension/teleportTo 的 POST_TELEPORT 机制）。
     */
    private static void ensureChunkForSubLevel(ServerLevel level, double x, double z) {
        int chunkX = (int) x >> 4;
        int chunkZ = (int) z >> 4;
        level.getChunk(chunkX, chunkZ);
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