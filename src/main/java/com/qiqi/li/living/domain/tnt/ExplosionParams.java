package com.qiqi.li.living.domain.tnt;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * 一次爆炸的完整参数 —— 可序列化进待炸账本，也可用于**逐区块**应用。
 *
 * <p><b>为什么要把参数抽出来</b>：爆炸的作用范围（球体）会跨几十上百个区块，而其中一部分
 * 可能未加载。把"这次爆炸是什么"参数化之后，破坏就可以**按区块**分帧执行、也可以在区块
 * 之后自然加载时补做 —— 见 {@link ExplosionLedger}。</p>
 *
 * <p><b>位图索引</b>：受影响区块是一个固定边长的方格网（{@code side = 2·chunkRadius+1}），
 * 用 {@code (dx, dz)} 的字典序映射成稳定下标 —— {@link #bitIndex} 与 {@link #chunkAt} 互逆。
 * <b>刻意不按圆形筛</b>：筛选与否只影响"要不要炸"（{@link #affects}），不影响索引，
 * 这样位图长度与映射永远稳定。</p>
 *
 * @param centerX      爆炸中心 X
 * @param centerY      爆炸中心 Y
 * @param centerZ      爆炸中心 Z
 * @param radius       爆炸半径（格）
 * @param mode         破坏方式
 * @param vanillaDrops 仅 {@link Mode#NORMAL} 有意义：true = 原版衰减掉落，false = 100% 掉落
 */
public record ExplosionParams(double centerX, double centerY, double centerZ,
                              double radius, Mode mode, boolean vanillaDrops) {

    /** 破坏方式 —— 与原先三个"当量模式"一一对应。 */
    public enum Mode {
        /** ≤64 TNT：逐个 setBlock，有掉落物。 */
        NORMAL,
        /** >64 TNT：直接改 Section 数据，无掉落物。 */
        HIGH_YIELD,
        /** >3456 TNT：整区块清空。 */
        SUPER
    }

    /** 受影响区块的半径（区块数）。 */
    public int chunkRadius() {
        return (int) Math.ceil(radius / 16.0);
    }

    /** 受影响区块方格网的边长（区块数）。 */
    public int side() {
        return 2 * chunkRadius() + 1;
    }

    /** 位图长度 = 方格网区块总数。 */
    public int chunkCount() {
        return side() * side();
    }

    public int centerChunkX() {
        return SectionPos.blockToSectionCoord((int) centerX);
    }

    public int centerChunkZ() {
        return SectionPos.blockToSectionCoord((int) centerZ);
    }

    /** 偏移 → 位图下标。 */
    public int bitIndex(int dx, int dz) {
        return (dx + chunkRadius()) * side() + (dz + chunkRadius());
    }

    /** 位图下标 → 区块坐标（{@link #bitIndex} 的逆）。 */
    public ChunkPos chunkAt(int bit) {
        int cr = chunkRadius();
        return new ChunkPos(centerChunkX() + bit / side() - cr,
                            centerChunkZ() + bit % side() - cr);
    }

    /** 指定区块在方格网里的位图下标；不在网内返回 -1。 */
    public int bitIndexOf(ChunkPos cp) {
        int dx = cp.x - centerChunkX();
        int dz = cp.z - centerChunkZ();
        if (Math.abs(dx) > chunkRadius() || Math.abs(dz) > chunkRadius()) return -1;
        return bitIndex(dx, dz);
    }

    /**
     * 该区块是否与爆炸的**球体**相交（决定"要不要炸"）。
     *
     * <p>⚠️ 判据是「<b>区块 AABB 与圆相交</b>」，<b>不是</b>「区块中心落在半径内」
     * （2026-09-22 修）。区块是 16×16 的方块，"中心在半径外、边缘却落在球内"的区块大量存在 ——
     * 按中心判定会把它们<b>整块跳过</b>（建档时就标记完成、永不处理），表现为
     * <b>坑不圆、边缘残留一整块区块形状的地形</b>。</p>
     *
     * <p>注：只判水平方向即可 —— 区块是贯穿整个世界高度的柱体，只要 (x,z) 与球体的水平投影
     * （半径 r 的圆）相交，块内就一定有落在球体里的方块。</p>
     */
    public boolean affects(ChunkPos cp) {
        int dx = cp.x - centerChunkX();
        int dz = cp.z - centerChunkZ();
        if (Math.abs(dx) > chunkRadius() || Math.abs(dz) > chunkRadius()) return false;

        // 区块 AABB 上离爆心最近的点（爆心落在区块内时，最近点就是爆心本身 ⇒ 距离 0）
        double nearestX = Math.max(cp.getMinBlockX(), Math.min(centerX, cp.getMaxBlockX()));
        double nearestZ = Math.max(cp.getMinBlockZ(), Math.min(centerZ, cp.getMaxBlockZ()));
        double ddx = nearestX - centerX;
        double ddz = nearestZ - centerZ;
        return ddx * ddx + ddz * ddz <= radius * radius;
    }
}
