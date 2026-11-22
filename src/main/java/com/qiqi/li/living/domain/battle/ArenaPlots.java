package com.qiqi.li.living.domain.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 竞技场定址与平台几何。
 *
 * <p>车道沿容器<b>行</b>（行 = 车道，列 = 纵深），怪从 GUI 右端（世界东端）往左推。
 * 世界观：lane 0 = 北（GUI 顶行），column 0 = 西（GUI 左列 = 战败线），观察者站南侧
 * 面向北观看 —— 左右与 GUI 一致。</p>
 *
 * <p>定址：battleKey hash → 负象限对角带 256 格间距网格（生产定案口径）。
 * 平台调试版用可见材质（草地 + 白混凝土隔断），生产换屏障 = 改下面两行常量。</p>
 */
public final class ArenaPlots {

    /** 车道步距 = 车道宽 2 + 隔断 1；纵深步距 = 每格 2。 */
    public static final int LANE_STRIDE = 3;
    public static final int DEPTH_STRIDE = 2;
    /** 平台地面 y（方块层；单位脚站在 y+1）。 */
    public static final int PLOT_Y = 220;

    /** 负象限对角带 x/z 起点（生产选址定案）与网格槽位数。 */
    private static final long BAND_MIN = -20_742_144L;
    private static final long BAND_SPAN = 262_144L;
    private static final long PLOT_SPACING = 256;
    private static final int SLOTS_PER_AXIS = (int) (BAND_SPAN / PLOT_SPACING);

    private ArenaPlots() {}

    /**
     * battleKey → plot 原点（确定性、零记账；碰撞概率低，alpha 不做归属检测）。
     * hash 两路独立混合，避免 x/z 强相关挤在对角线上。
     */
    public static BlockPos plotFor(String battleKey) {
        long h = battleKey == null ? 0L : battleKey.hashCode();
        int slotX = (int) Long.remainderUnsigned(mix(h), SLOTS_PER_AXIS);
        int slotZ = (int) Long.remainderUnsigned(mix(h * 0x9E3779B97F4A7C15L + 0x51ED270B), SLOTS_PER_AXIS);
        return new BlockPos((int) (BAND_MIN + (long) slotX * PLOT_SPACING), PLOT_Y,
            (int) (BAND_MIN + (long) slotZ * PLOT_SPACING));
    }

    private static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xFF51AFD7ED558CCDL;
        v ^= v >>> 33;
        v *= 0xC4CEB9FE1A85EC53L;
        v ^= v >>> 33;
        return v;
    }

    /** 调试 near 模式：玩家旁 ~12 格，抬高可见（透过容器 GUI 半透明背景）。 */
    public static BlockPos nearFor(Vec3 center) {
        return BlockPos.containing(center.x + 12, Math.max(center.y + 10, 80), center.z);
    }

    /** 槽位 → 战格中心（lane = 行 = slot/width，col = 列 = slot%width）。 */
    public static Vec3 cellCenter(BlockPos origin, int lane, int col) {
        return new Vec3(origin.getX() + col * DEPTH_STRIDE + 1.0, origin.getY() + 1.0,
            origin.getZ() + lane * LANE_STRIDE + 0.5);
    }

    /** 车道条带（推进 Goal 的索敌范围；lane 行宽 2 含隔断余量）。 */
    public static AABB laneStrip(BlockPos origin, int depth, int lane) {
        return new AABB(origin.getX() - 2, origin.getY(), origin.getZ() + lane * LANE_STRIDE - 0.5,
            origin.getX() + depth * DEPTH_STRIDE + 2, origin.getY() + 8,
            origin.getZ() + lane * LANE_STRIDE + 2.5);
    }

    /** 战败线锚点（第 0 列西侧一格外；无桥时推进目标）。 */
    public static Vec3 columnAnchor(BlockPos origin, int lane) {
        return new Vec3(origin.getX() - 2.5, origin.getY() + 1.0, origin.getZ() + lane * LANE_STRIDE + 0.5);
    }

    /** 战区 AABB（事件圈定 / 清场扫描）。 */
    public static AABB plotBounds(BlockPos origin, int lanes, int depth) {
        return new AABB(origin.getX() - 3, origin.getY(), origin.getZ() - 2,
            origin.getX() + depth * DEPTH_STRIDE + 3, origin.getY() + 80,
            origin.getZ() + lanes * LANE_STRIDE + 2);
    }

    /** 敌军推进进度：0 = 第 0 列（GUI 左），1 = 东端（GUI 右）。 */
    public static double progressOf(double x, BlockPos origin, int depth) {
        double p = (x - origin.getX()) / (double) (depth * DEPTH_STRIDE);
        return Math.max(0.0, Math.min(1.0, p));
    }

    /** 战区各区块是否实体 tick（就绪门）：未加载 = 战局暂停。 */
    public static boolean isEntityTicking(ServerLevel level, BlockPos origin, int lanes, int depth) {
        int minX = (origin.getX() - 2) >> 4;
        int maxX = (origin.getX() + depth * DEPTH_STRIDE + 2) >> 4;
        int minZ = (origin.getZ() - 2) >> 4;
        int maxZ = (origin.getZ() + lanes * LANE_STRIDE + 2) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!level.isPositionEntityTicking(new BlockPos(cx << 4, origin.getY(), cz << 4))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 一次性 fill 平台：地面草地 + 车道隔断白混凝土。
     *
     * <p>⚠️ spike 简化：命令驱动的一次性同步加载（{@code getChunk(FULL, force=true)}，
     * 只覆盖平台几个区块）。生产版按 infrastructure §3.2.2 方案 A 走异步票据，此处不是热路径。</p>
     */
    public static void fillPlatform(ServerLevel level, BlockPos origin, int lanes, int depth) {
        int widthX = depth * DEPTH_STRIDE;
        int widthZ = lanes * LANE_STRIDE - 1;
        int minX = (origin.getX() - 1) >> 4;
        int maxX = (origin.getX() + widthX + 1) >> 4;
        int minZ = (origin.getZ() - 1) >> 4;
        int maxZ = (origin.getZ() + widthZ + 1) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        for (int x = 0; x <= widthX; x++) {
            for (int z = 0; z < widthZ; z++) {
                boolean divider = (z % LANE_STRIDE) == LANE_STRIDE - 1;
                level.setBlock(new BlockPos(origin.getX() + x, origin.getY(), origin.getZ() + z),
                    divider ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(),
                    3);
            }
        }
    }

    /** 清平台方块（clear 命令；只清自己的足迹，不碰平台外世界）。 */
    public static void clearPlatform(ServerLevel level, BlockPos origin, int lanes, int depth) {
        int widthX = depth * DEPTH_STRIDE;
        int widthZ = lanes * LANE_STRIDE - 1;
        for (int x = 0; x <= widthX; x++) {
            for (int z = 0; z < widthZ; z++) {
                level.removeBlock(new BlockPos(origin.getX() + x, origin.getY(), origin.getZ() + z), false);
            }
        }
    }

    /** 平台地面层的顶面 y（单位脚位置）。 */
    public static double floorY(BlockPos origin) {
        return origin.getY() + 1.0;
    }
}
