package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("affects 判据 = 区块中心距中心 ≤ radius（与旧 scheduleSuperExplosion 同口径）")
    void affects_matchesLegacyCriterion() {
        ExplosionParams p = params(16); // chunkRadius=1 → 3×3 网格
        assertEquals(9, p.chunkCount());

        // 正十字 5 格命中；四角中心距 = 16√2 ≈ 22.6 > 16 ⇒ 不命中
        assertTrue(p.affects(new ChunkPos(0, 0)));
        assertTrue(p.affects(new ChunkPos(1, 0)));
        assertTrue(p.affects(new ChunkPos(-1, 0)));
        assertTrue(p.affects(new ChunkPos(0, 1)));
        assertTrue(p.affects(new ChunkPos(0, -1)));
        assertFalse(p.affects(new ChunkPos(1, 1)), "四角距离超半径，不该被炸");
        assertFalse(p.affects(new ChunkPos(-1, -1)));
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
