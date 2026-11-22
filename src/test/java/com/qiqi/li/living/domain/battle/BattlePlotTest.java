package com.qiqi.li.living.domain.battle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class BattlePlotTest {

    private static final long BAND_MIN = -20_742_144L;
    private static final long BAND_MAX = -20_480_000L;
    private static final long SPACING = 256;

    @Test
    void sameKeyMapsDeterministically() {
        BlockPos a = ArenaPlots.plotFor("chest_100_64_200");
        BlockPos b = ArenaPlots.plotFor("chest_100_64_200");
        assertEquals(a, b);
    }

    @Test
    void plotsStayInsideNegativeQuadrantBand() {
        for (int i = 0; i < 64; i++) {
            BlockPos plot = ArenaPlots.plotFor("key_" + i);
            assertTrue(plot.getX() >= BAND_MIN && plot.getX() < BAND_MAX,
                "x 越带：" + plot.getX());
            assertTrue(plot.getZ() >= BAND_MIN && plot.getZ() < BAND_MAX,
                "z 越带：" + plot.getZ());
            assertEquals(ArenaPlots.PLOT_Y, plot.getY());
        }
    }

    @Test
    void plotsLandOnSpacingGrid() {
        for (int i = 0; i < 64; i++) {
            BlockPos plot = ArenaPlots.plotFor("grid_" + i);
            assertEquals(0, (plot.getX() - BAND_MIN) % SPACING, "x 未对齐网格");
            assertEquals(0, (plot.getZ() - BAND_MIN) % SPACING, "z 未对齐网格");
        }
    }

    @Test
    void distinctKeysSpreadAcrossGrid() {
        java.util.Set<Long> xs = new java.util.HashSet<>();
        for (int i = 0; i < 32; i++) {
            xs.add((long) ArenaPlots.plotFor("spread_" + i).getX());
        }
        assertTrue(xs.size() > 1, "不同 key 全部撞同一槽位 ⇒ hash 退化");
    }

    @Test
    void nearModeOffsetsFromPlayerAndLifts() {
        Vec3 center = new Vec3(100.5, 64.0, -200.5);
        BlockPos near = ArenaPlots.nearFor(center);
        assertTrue(Math.abs(near.getX() - center.x - 12) <= 1, "near 应在玩家东偏 ~12 格");
        assertTrue(near.getY() > center.y, "near 应抬高可见");
    }

    @Test
    void cellCenterFollowsRowColumnMath() {
        BlockPos origin = new BlockPos(1000, 220, 2000);
        Vec3 c00 = ArenaPlots.cellCenter(origin, 0, 0);
        assertEquals(origin.getX() + 1.0, c00.x, 1.0E-9);
        assertEquals(origin.getZ() + 0.5, c00.z, 1.0E-9);
        Vec3 c12 = ArenaPlots.cellCenter(origin, 1, 2);
        assertEquals(origin.getX() + 2 * ArenaPlots.CELL_STRIDE + 1.0, c12.x, 1.0E-9);
        assertEquals(origin.getZ() + ArenaPlots.CELL_STRIDE + 0.5, c12.z, 1.0E-9);
    }

    @Test
    void rowColClampToArenaRange() {
        BlockPos origin = new BlockPos(0, 220, 0);
        assertEquals(0.0, ArenaPlots.rowOf(-50, origin, 3), 1.0E-9, "北侧外夹取 0");
        assertEquals(2.0, ArenaPlots.rowOf(500, origin, 3), 1.0E-9, "南侧外夹取 lanes-1");
        assertEquals(1.5, ArenaPlots.rowOf(3.0, origin, 3), 1.0E-9, "行带间插值");
        assertEquals(0.0, ArenaPlots.colOf(-50, origin, 9), 1.0E-9, "西侧外夹取 0");
        assertEquals(8.0, ArenaPlots.colOf(500, origin, 9), 1.0E-9, "东侧外夹取 depth-1");
        assertEquals(1.5, ArenaPlots.colOf(3.0, origin, 9), 1.0E-9, "列间插值");
    }
}
