package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
