package com.qiqi.li.living.domain.tnt;

import java.util.Objects;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
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

    public ExplosionParams {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
            throw new IllegalArgumentException("Explosion center must be finite");
        }
        if (!Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Explosion radius must be finite and positive");
        }
        Objects.requireNonNull(mode, "mode");
    }

    /** 受影响区块的半径（区块数）；超出 int 范围时拒绝位图计算。 */
    public int chunkRadius() {
        double chunks = Math.ceil(radius / 16.0);
        if (chunks > Integer.MAX_VALUE) throw new ArithmeticException("Chunk radius exceeds int range");
        return Math.toIntExact(Math.max(1L, (long) chunks));
    }

    /** 受影响区块方格网的边长（区块数）。 */
    public int side() {
        return Math.toIntExact(Math.addExact(Math.multiplyExact((long) chunkRadius(), 2L), 1L));
    }

    /** 位图长度 = 方格网区块总数；必须能由 int 下标寻址。 */
    public int chunkCount() {
        long side = side();
        return Math.toIntExact(Math.multiplyExact(side, side));
    }

    /** 位图准入检查：网格规模与全部区块坐标必须可表示。 */
    void validateBitmapLayout() {
        if (centerX < Integer.MIN_VALUE || centerX > Integer.MAX_VALUE
                || centerY < Integer.MIN_VALUE || centerY > Integer.MAX_VALUE
                || centerZ < Integer.MIN_VALUE || centerZ > Integer.MAX_VALUE) {
            throw new ArithmeticException("Bitmap center exceeds block coordinate range");
        }
        chunkCount();
        int cr = chunkRadius();
        Math.toIntExact((long) centerChunkX() - cr);
        Math.toIntExact((long) centerChunkX() + cr);
        Math.toIntExact((long) centerChunkZ() - cr);
        Math.toIntExact((long) centerChunkZ() + cr);
    }

    public int voxelCenterX() {
        return Mth.floor(centerX);
    }

    public int voxelCenterY() {
        return Mth.floor(centerY);
    }

    public int voxelCenterZ() {
        return Mth.floor(centerZ);
    }

    public int centerChunkX() {
        return SectionPos.blockToSectionCoord(voxelCenterX());
    }

    public int centerChunkZ() {
        return SectionPos.blockToSectionCoord(voxelCenterZ());
    }

    /** 偏移 → 位图下标；偏移必须在网格内。 */
    public int bitIndex(int dx, int dz) {
        int cr = chunkRadius();
        chunkCount();
        if (dx < -cr || dx > cr || dz < -cr || dz > cr) {
            throw new IndexOutOfBoundsException("Chunk offset outside bitmap grid");
        }
        long row = (long) dx + cr;
        long column = (long) dz + cr;
        return Math.toIntExact(Math.addExact(Math.multiplyExact(row, (long) side()), column));
    }

    /** 位图下标 → 区块坐标（{@link #bitIndex} 的逆）；下标必须在网格内。 */
    public ChunkPos chunkAt(int bit) {
        int cr = chunkRadius();
        int side = side();
        if (bit < 0 || bit >= chunkCount()) {
            throw new IndexOutOfBoundsException("Bit index outside bitmap grid");
        }
        return new ChunkPos(Math.toIntExact((long) centerChunkX() + bit / side - cr),
                            Math.toIntExact((long) centerChunkZ() + bit % side - cr));
    }

    /** 指定区块在方格网里的位图下标；不在网内返回 -1。 */
    public int bitIndexOf(ChunkPos cp) {
        long dx = (long) cp.x - centerChunkX();
        long dz = (long) cp.z - centerChunkZ();
        int cr = chunkRadius();
        chunkCount();
        if (dx < -cr || dx > cr || dz < -cr || dz > cr) return -1;
        return bitIndex(Math.toIntExact(dx), Math.toIntExact(dz));
    }

    /**
     * 该区块是否与爆炸球体的水平投影相交；不依赖位图规模。
     * 区块 AABB 包含最小与最大方块坐标，圆周相切也命中。
     */
    public boolean affects(ChunkPos cp) {
        long minX = (long) cp.x * 16L;
        long minZ = (long) cp.z * 16L;
        if (mode != Mode.SUPER) {
            int centerX = voxelCenterX();
            int centerZ = voxelCenterZ();
            double nearestX = Math.max(minX, Math.min(centerX, minX + 15L));
            double nearestZ = Math.max(minZ, Math.min(centerZ, minZ + 15L));
            return Math.hypot(nearestX - centerX, nearestZ - centerZ) <= radius;
        }
        double nearestX = Math.max(minX, Math.min(centerX(), minX + 15L));
        double nearestZ = Math.max(minZ, Math.min(centerZ(), minZ + 15L));
        return Math.hypot(nearestX - centerX(), nearestZ - centerZ()) <= radius;
    }
}
