package com.qiqi.li.living.domain.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 竞技场定址与场地几何（自由围猎口径，D-battle-05）。
 *
 * <p>场地 = 开阔平台，无车道无隔断；容器槽位网格 1:1 投影为战场格子
 * （行=南北、列=东西，每格 {@link #CELL_STRIDE}² 见方），敌军全场自由移动，
 * 索敌只认"最近棋子"。</p>
 *
 * <p>定址：battleKey hash → 负象限对角带 256 格间距网格（生产定案口径）。
 * 平台调试版用可见材质（草地 + 外沿石），生产换屏障 = 改下面常量。</p>
 */
public final class ArenaPlots {

    /** 战场格步距（x/z 同距；桥 hitbox 宽 1，留活动余量）。 */
    public static final int CELL_STRIDE = 2;
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

    /** 槽位 → 战格中心（row = 南北 = slot/width，col = 东西 = slot%width）。 */
    public static Vec3 cellCenter(BlockPos origin, int row, int col) {
        return new Vec3(origin.getX() + col * CELL_STRIDE + 1.0, origin.getY() + 1.0,
            origin.getZ() + row * CELL_STRIDE + 0.5);
    }

    /** 战区 AABB（索敌范围 / 事件圈定 / 清场扫描）。 */
    public static AABB plotBounds(BlockPos origin, int lanes, int depth) {
        return new AABB(origin.getX() - 3, origin.getY(), origin.getZ() - 2,
            origin.getX() + depth * CELL_STRIDE + 3, origin.getY() + 80,
            origin.getZ() + lanes * CELL_STRIDE + 2);
    }

    /** 世界坐标 → 网格坐标（row/col 浮点；夹取到场地范围内）。 */
    public static double rowOf(double z, BlockPos origin, int lanes) {
        double r = (z - origin.getZ()) / (double) CELL_STRIDE;
        return Math.max(0.0, Math.min(lanes - 1.0, r));
    }

    public static double colOf(double x, BlockPos origin, int depth) {
        double c = (x - origin.getX()) / (double) CELL_STRIDE;
        return Math.max(0.0, Math.min(depth - 1.0, c));
    }

    /** 战区各区块是否实体 tick（就绪门）：未加载 = 战局暂停。 */
    public static boolean isEntityTicking(ServerLevel level, BlockPos origin, int lanes, int depth) {
        int minX = (origin.getX() - 2) >> 4;
        int maxX = (origin.getX() + depth * CELL_STRIDE + 2) >> 4;
        int minZ = (origin.getZ() - 2) >> 4;
        int maxZ = (origin.getZ() + lanes * CELL_STRIDE + 2) >> 4;
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
     * 一次性 fill 平台：开阔草地 + 外沿一圈石（出怪红边视觉参照，自由模式下无车道隔断）。
     *
     * <p>⚠️ spike 简化：命令驱动的一次性同步加载（{@code getChunk(FULL, force=true)}，
     * 只覆盖平台几个区块）。生产版按 infrastructure §3.2.2 方案 A 走异步票据，此处不是热路径。</p>
     */
    public static void fillPlatform(ServerLevel level, BlockPos origin, int lanes, int depth) {
        int widthX = depth * CELL_STRIDE;
        int widthZ = lanes * CELL_STRIDE;
        int minX = (origin.getX() - 1) >> 4;
        int maxX = (origin.getX() + widthX + 1) >> 4;
        int minZ = (origin.getZ() - 1) >> 4;
        int maxZ = (origin.getZ() + widthZ + 1) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        for (int x = 0; x < widthX; x++) {
            for (int z = 0; z < widthZ; z++) {
                boolean border = x == 0 || z == 0 || x == widthX - 1 || z == widthZ - 1;
                level.setBlock(new BlockPos(origin.getX() + x, origin.getY(), origin.getZ() + z),
                    border ? Blocks.STONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(),
                    3);
            }
        }
    }

    /** 清平台方块（clear 命令；只清自己的足迹，不碰平台外世界）。 */
    public static void clearPlatform(ServerLevel level, BlockPos origin, int lanes, int depth) {
        int widthX = depth * CELL_STRIDE;
        int widthZ = lanes * CELL_STRIDE;
        for (int x = 0; x < widthX; x++) {
            for (int z = 0; z < widthZ; z++) {
                level.removeBlock(new BlockPos(origin.getX() + x, origin.getY(), origin.getZ() + z), false);
            }
        }
    }
}
