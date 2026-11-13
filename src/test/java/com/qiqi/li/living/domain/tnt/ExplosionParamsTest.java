package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * {@link ExplosionParams} 的几何与位图映射守卫（2026-09-18）。
 *
 * <p>这些映射是**待炸账本正确性的地基**：位图下标必须稳定可逆，否则存档往返或分帧补炸
 * 会把破坏应用到错误的区块上。所以这里钉的是"可逆性"与"判据一致性"，不是实现细节。</p>
 */
class ExplosionParamsTest {

    private static ExplosionParams params(double radius) {
        // 中心刻意放在区块 (0,0) 的中间偏一点，覆盖"中心不在区块正中"的常见情形
        return new ExplosionParams(8, 64, 8, radius, ExplosionParams.Mode.NORMAL, true);
    }

    @Test
    @DisplayName("位图映射可逆：bitIndex ↔ chunkAt 对全网格逐位往返一致")
    void bitMapping_isReversible() {
        ExplosionParams p = params(235); // 最大档：chunkRadius=15, side=31, 961 位

        assertEquals(31, p.side());
        assertEquals(961, p.chunkCount());

        for (int bit = 0; bit < p.chunkCount(); bit++) {
            ChunkPos cp = p.chunkAt(bit);
            assertEquals(bit, p.bitIndexOf(cp), "往返不一致：bit=" + bit + " pos=" + cp);
        }
    }

    @Test
    @DisplayName("网格外的区块返回 -1（不能误当成 0 号位）")
    void bitIndexOf_outsideGrid_isMinusOne() {
        ExplosionParams p = params(32); // chunkRadius=2 → 网格 [-2,2]
        assertEquals(-1, p.bitIndexOf(new ChunkPos(3, 0)));
        assertEquals(-1, p.bitIndexOf(new ChunkPos(0, -3)));
        assertEquals(-1, p.bitIndexOf(new ChunkPos(99, 99)));
        assertTrue(p.bitIndexOf(new ChunkPos(2, -2)) >= 0, "网格边界内必须命中");
    }

    @Test
    @DisplayName("affects 判据 = 区块 AABB 与球体相交（不是区块中心落在半径内）")
    void affects_isChunkAabbIntersectingSphere() {
        ExplosionParams p = params(16); // chunkRadius=1 → 3×3 网格
        assertEquals(9, p.chunkCount());

        // 半径 16 的圆以 (8,8) 为心：3×3 网格里每个区块都至少有一角落在圆内
        for (int bit = 0; bit < p.chunkCount(); bit++) {
            assertTrue(p.affects(p.chunkAt(bit)), "3×3 网格内的区块都应与圆相交：" + p.chunkAt(bit));
        }
    }

    @Test
    @DisplayName("⚠️ 边界回归：区块中心在半径外、边缘却落在球内 ⇒ 必须命中（否则坑边缘残留整块区块）")
    void affects_rimChunkWithCenterOutside_isAffected() {
        ExplosionParams p = params(16);

        // 区块 (1,1) 中心 (24,24) 距心 16√2 ≈ 22.6 > 16；最近角 (16,16) 距心 11.3 ≤ 16。
        // 按"中心判定"会被整块跳过 —— 这正是坑不圆、边缘残留方块形状地形的根因（2026-09-22）。
        ChunkPos rim = new ChunkPos(1, 1);
        double centerDist = Math.hypot((rim.x << 4) + 8 - 8, (rim.z << 4) + 8 - 8);
        assertTrue(centerDist > p.radius(), "用例前提：该区块中心确实在半径外，实测 " + centerDist);

        assertTrue(p.affects(rim),
            "中心在半径外但边缘在球内的区块必须命中 —— 否则它整块地形都留着，坑边缘会出现方形残留");
    }

    @Test
    @DisplayName("超出 chunkRadius 的方形网格一律不命中（与 bitIndexOf 同边界）")
    void affects_outsideGrid_isFalse() {
        ExplosionParams p = params(16); // chunkRadius=1
        assertFalse(p.affects(new ChunkPos(2, 0)));
        assertFalse(p.affects(new ChunkPos(0, -2)));
        assertFalse(p.affects(new ChunkPos(99, 99)));
    }

    @Test
    @DisplayName("⚠️ 球体全覆盖：半径内**每个方块**所在区块都必须命中（否则坑不圆、残留整块区块）")
    void affects_coversEveryBlockInsideSphere() {
        ExplosionParams p = params(32); // chunkRadius=2 → 5×5 网格
        int r = (int) Math.ceil(p.radius());
        int cx = (int) p.centerX();
        int cz = (int) p.centerZ();
        double r2 = p.radius() * p.radius();

        // 按区块缓存（同一区块会被几千个方块问到）
        Map<Long, Boolean> cache = new HashMap<>();
        int checked = 0;

        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double dx = x - cx;
                double dz = z - cz;
                if (dx * dx + dz * dz > r2) continue;
                checked++;

                ChunkPos cp = new ChunkPos(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                boolean affected = cache.computeIfAbsent(cp.toLong(), k -> p.affects(cp));
                assertTrue(affected,
                    "半径内的方块 (" + x + "," + z + ") 落在**未被处理**的区块 " + cp
                        + " 里 —— 那块地形会整块留着，坑就不圆了");
            }
        }

        assertTrue(checked > 3000, "用例前提：球体水平投影要覆盖足够多方块，实测 " + checked);
    }

    @Test
    @DisplayName("半径 → 网格规模：ceil(r/16) 与 2n+1")
    void chunkRadiusAndSide() {
        assertEquals(1, params(1).chunkRadius());
        assertEquals(1, params(16).chunkRadius());
        assertEquals(2, params(16.1).chunkRadius());
        assertEquals(2, params(32).chunkRadius());   // 64 TNT ⇒ radius 32 ⇒ 5×5 区块
        assertEquals(15, params(235).chunkRadius()); // 3456 TNT ⇒ radius 235 ⇒ 31×31 区块
    }

    @Test
    @DisplayName("参数准入：有限中心、有限正半径、非空模式")
    void constructor_rejectsInvalidParameters() {
        for (double invalid : new double[] { Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class,
                () -> new ExplosionParams(invalid, 64, 8, 16, ExplosionParams.Mode.NORMAL, true));
            assertThrows(IllegalArgumentException.class,
                () -> new ExplosionParams(8, invalid, 8, 16, ExplosionParams.Mode.NORMAL, true));
            assertThrows(IllegalArgumentException.class,
                () -> new ExplosionParams(8, 64, invalid, 16, ExplosionParams.Mode.NORMAL, true));
            assertThrows(IllegalArgumentException.class, () -> params(invalid));
        }
        for (double invalid : new double[] { -1, 0, -0.0 }) {
            assertThrows(IllegalArgumentException.class, () -> params(invalid));
        }
        assertThrows(NullPointerException.class, () -> new ExplosionParams(8, 64, 8, 16, null, true));
        assertEquals(1, params(Double.MIN_VALUE).chunkRadius());
        assertDoesNotThrow(() -> params(Double.MAX_VALUE));
    }

    @Test
    @DisplayName("自定义半径：不受 961 位或默认当量档限制，不改变模式")
    void bitmapLayout_acceptsCustomRadii() {
        for (ExplosionParams.Mode mode : ExplosionParams.Mode.values()) {
            ExplosionParams p = new ExplosionParams(8, 64, 8, 1024, mode, true);
            assertDoesNotThrow(p::validateBitmapLayout);
            assertEquals(64, p.chunkRadius());
            assertEquals(129, p.side());
            assertEquals(16641, p.chunkCount());
            assertEquals(mode, p.mode());
            assertEquals(p.chunkCount() - 1, p.bitIndex(64, 64));
            assertEquals(p.chunkCount() - 1, p.bitIndexOf(p.chunkAt(p.chunkCount() - 1)));
        }
    }

    @Test
    @DisplayName("位图算术：半径、边长、面积越过 int 时明确拒绝，不回绕")
    void bitmapArithmetic_rejectsOverflow() {
        ExplosionParams maxRadius = params(Integer.MAX_VALUE * 16.0);
        assertEquals(Integer.MAX_VALUE, maxRadius.chunkRadius());
        assertThrows(ArithmeticException.class, maxRadius::side);
        assertThrows(ArithmeticException.class, () -> params((Integer.MAX_VALUE + 1.0) * 16.0).chunkRadius());
        assertThrows(ArithmeticException.class, () -> params(Double.MAX_VALUE).chunkRadius());

        ExplosionParams maxSide = params(((Integer.MAX_VALUE - 1L) / 2L) * 16.0);
        assertEquals(Integer.MAX_VALUE, maxSide.side());
        assertThrows(ArithmeticException.class, maxSide::chunkCount);

        ExplosionParams largestArea = params(23169 * 16.0);
        assertEquals(2147302921, largestArea.chunkCount());
        assertDoesNotThrow(largestArea::validateBitmapLayout);
        assertEquals(largestArea.chunkCount() - 1, largestArea.bitIndex(23169, 23169));
        assertEquals(largestArea.chunkCount() - 1,
            largestArea.bitIndexOf(largestArea.chunkAt(largestArea.chunkCount() - 1)));

        ExplosionParams overflowingArea = params(23170 * 16.0);
        assertEquals(46341, overflowingArea.side());
        assertThrows(ArithmeticException.class, overflowingArea::chunkCount);
        assertThrows(ArithmeticException.class, overflowingArea::validateBitmapLayout);
        assertThrows(ArithmeticException.class, () -> overflowingArea.bitIndex(0, 0));
        assertThrows(ArithmeticException.class, () -> overflowingArea.chunkAt(0));
    }

    @Test
    @DisplayName("位图下标与偏移必须在网格内，极端 int 偏移不能回绕成有效位")
    void bitMapping_rejectsInvalidOffsetsAndIndices() {
        ExplosionParams p = params(32);
        for (int outside : new int[] { -3, 3, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            assertThrows(IndexOutOfBoundsException.class, () -> p.bitIndex(outside, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> p.bitIndex(0, outside));
        }
        for (int outside : new int[] { -1, p.chunkCount(), Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            assertThrows(IndexOutOfBoundsException.class, () -> p.chunkAt(outside));
        }
        assertEquals(0, p.bitIndex(-2, -2));
        assertEquals(p.chunkCount() - 1, p.bitIndex(2, 2));
        assertEquals(-1, p.bitIndexOf(new ChunkPos(Integer.MIN_VALUE, 0)));
        assertEquals(-1, p.bitIndexOf(new ChunkPos(0, Integer.MIN_VALUE)));
        assertEquals(-1, p.bitIndexOf(new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE)));
    }

    @Test
    @DisplayName("位图准入拒绝不可表示的方块中心；有限远端中心仍可用于几何判定")
    void bitmapLayout_rejectsUnrepresentableCenters() {
        for (double outside : new double[] { Integer.MIN_VALUE - 1.0, Integer.MAX_VALUE + 1.0, Double.MAX_VALUE }) {
            assertThrows(ArithmeticException.class,
                () -> new ExplosionParams(outside, 64, 8, 16, ExplosionParams.Mode.NORMAL, true).validateBitmapLayout());
            assertThrows(ArithmeticException.class,
                () -> new ExplosionParams(8, outside, 8, 16, ExplosionParams.Mode.NORMAL, true).validateBitmapLayout());
            assertThrows(ArithmeticException.class,
                () -> new ExplosionParams(8, 64, outside, 16, ExplosionParams.Mode.NORMAL, true).validateBitmapLayout());
        }
        assertDoesNotThrow(() -> new ExplosionParams(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            16, ExplosionParams.Mode.NORMAL, true).validateBitmapLayout());
    }

    @Test
    @DisplayName("中心区块保持既有浮点转 int 规则，不顺带改变负数小数中心")
    void centerChunks_preserveExistingConversion() {
        ExplosionParams p = new ExplosionParams(-0.5, 64, -16.5, 16, ExplosionParams.Mode.NORMAL, true);
        assertEquals(0, p.centerChunkX());
        assertEquals(-1, p.centerChunkZ());
        assertEquals(0, p.bitIndexOf(p.chunkAt(0)));
    }

    @Test
    @DisplayName("几何判定保持 AABB 的最小/最大方块坐标与圆周相切包含性")
    void affects_preservesInclusiveAabbAndCircleBoundary() {
        ChunkPos target = new ChunkPos(1, 1);
        ExplosionParams tangent = new ExplosionParams(13, 64, 12, 5, ExplosionParams.Mode.SUPER, true);
        assertTrue(tangent.affects(target));
        assertFalse(new ExplosionParams(13, 64, 12, Math.nextDown(5.0), ExplosionParams.Mode.SUPER, true)
            .affects(target));
        assertTrue(new ExplosionParams(31, 64, 31, Double.MIN_VALUE, ExplosionParams.Mode.SUPER, true)
            .affects(target));
        assertFalse(new ExplosionParams(31.5, 64, 31, 0.25, ExplosionParams.Mode.SUPER, true)
            .affects(target));
    }

    @Test
    @DisplayName("几何判定使用宽区块边界，极端区块坐标不能回绕到原点")
    void affects_usesWidenedChunkBounds() {
        ChunkPos far = new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE);
        double x = (long) far.x * 16L + 8L;
        double z = (long) far.z * 16L + 8L;
        ExplosionParams p = new ExplosionParams(x, 64, z, 1, ExplosionParams.Mode.SUPER, true);
        assertTrue(p.affects(far));
        assertFalse(p.affects(new ChunkPos(far.x - 1, far.z)));
        assertFalse(p.affects(new ChunkPos(0, 0)));
        assertFalse(params(16).affects(far));
        assertFalse(params(16).affects(new ChunkPos(Integer.MIN_VALUE, 0)));
    }

    @Test
    @DisplayName("SUPER 几何不依赖位图半径或面积，巨型有限半径与中心不做平方溢出比较")
    void affects_isIndependentOfBitmapLimits() {
        ExplosionParams huge = new ExplosionParams(8, 64, 8, Double.MAX_VALUE, ExplosionParams.Mode.SUPER, true);
        assertThrows(ArithmeticException.class, huge::chunkCount);
        assertTrue(huge.affects(new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE)));

        ExplosionParams areaOverflow = new ExplosionParams(8, 64, 8, 23170 * 16.0,
            ExplosionParams.Mode.SUPER, true);
        assertThrows(ArithmeticException.class, areaOverflow::chunkCount);
        assertTrue(areaOverflow.affects(new ChunkPos(20000, 0)));
        assertFalse(areaOverflow.affects(new ChunkPos(30000, 0)));

        ExplosionParams farCenter = new ExplosionParams(1e200, 64, 0, 1e190, ExplosionParams.Mode.SUPER, true);
        assertFalse(farCenter.affects(new ChunkPos(0, 0)));
        ExplosionParams farCovering = new ExplosionParams(1e200, 64, 0, 1e200, ExplosionParams.Mode.SUPER, true);
        assertTrue(farCovering.affects(new ChunkPos(0, 0)));
    }
}
