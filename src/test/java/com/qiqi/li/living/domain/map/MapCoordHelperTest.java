package com.qiqi.li.living.domain.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * {@link MapCoordHelper} 坐标换算测试。
 *
 * <p>覆盖不依赖 {@code MapItemSavedData} 的纯算术方法：
 * 展示框命中点 → UV → 地图像素、地图中心网格对齐、射线到地图边界的距离。
 * 这些换算一旦出错，活地图传送会落到错误坐标，而症状难以从日志定位。</p>
 */
class MapCoordHelperTest {

    private static final BlockPos FRAME = new BlockPos(0, 0, 0);
    private static final double EPS = 1e-9;

    // ════════════════════════════════════════
    // UV → 地图像素
    // ════════════════════════════════════════

    @ParameterizedTest(name = "u={0} → mapX={1}")
    @CsvSource({
        "0.0,   0",
        "0.5,   64",
        "0.999, 127",
        "0.25,  32"
    })
    @DisplayName("UV 转地图像素：128 格线性映射并向下取整")
    void uvToMapX_mapsLinearlyTo128Pixels(double u, int expected) {
        assertEquals(expected, MapCoordHelper.uvToMapX(u));
    }

    @Test
    @DisplayName("UV 转地图像素：X 与 Y 使用相同映射")
    void uvToMapY_matchesUvToMapX() {
        for (double t = 0.0; t < 1.0; t += 0.05) {
            assertEquals(MapCoordHelper.uvToMapX(t), MapCoordHelper.uvToMapY(t));
        }
    }

    // ════════════════════════════════════════
    // 展示框命中点 → UV
    // ════════════════════════════════════════

    @Test
    @DisplayName("墙面展示框：命中中心点得到 UV 中心")
    void hitVecToMapPixel_centerHitOnWall_returnsCenterUv() {
        // NORTH 面，命中方块中心
        var result = MapCoordHelper.hitVecToMapPixel(
            new Vec3(0.5, 0.5, 0.0), FRAME, Direction.NORTH, 0);

        assertEquals(0.5, result.u(), EPS);
        assertEquals(0.5, result.v(), EPS);
    }

    /**
     * 四个水平朝向在命中各自方块中心时都应得到 UV 中心。
     *
     * <p>这是"无论地图挂在哪面墙上，点中间就传送到地图中心"的保证。</p>
     */
    @ParameterizedTest(name = "facing={0}")
    @CsvSource({
        "NORTH", "SOUTH", "EAST", "WEST"
    })
    @DisplayName("四个水平朝向：命中中心均得到 UV 中心")
    void hitVecToMapPixel_allHorizontalFacings_centerMapsToCenter(String facingName) {
        Direction facing = Direction.valueOf(facingName);
        // 对 NORTH/SOUTH 变化 x，对 EAST/WEST 变化 z，统一取 0.5
        var result = MapCoordHelper.hitVecToMapPixel(
            new Vec3(0.5, 0.5, 0.5), FRAME, facing, 0);

        assertEquals(0.5, result.u(), EPS, facing + " 的 u 应为 0.5");
        assertEquals(0.5, result.v(), EPS, facing + " 的 v 应为 0.5");
    }

    @Test
    @DisplayName("旋转 180°：UV 相对无旋转呈中心对称")
    void hitVecToMapPixel_rotation180_isCentrallySymmetric() {
        Vec3 hit = new Vec3(0.25, 0.75, 0.0);

        var noRot = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 0);
        var rot180 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 2);

        assertEquals(1 - noRot.u(), rot180.u(), EPS, "旋转 180° 后 u 应取反");
        assertEquals(1 - noRot.v(), rot180.v(), EPS, "旋转 180° 后 v 应取反");
    }

    @Test
    @DisplayName("旋转 360°（rot=4）等价于无旋转")
    void hitVecToMapPixel_rotation4_equalsNoRotation() {
        Vec3 hit = new Vec3(0.3, 0.7, 0.0);

        var rot0 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 0);
        var rot4 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 4);

        assertEquals(rot0.u(), rot4.u(), EPS);
        assertEquals(rot0.v(), rot4.v(), EPS);
    }

    @Test
    @DisplayName("旋转 90° 四次回到原点")
    void hitVecToMapPixel_fourNinetyDegreeRotations_returnToOrigin() {
        Vec3 hit = new Vec3(0.2, 0.6, 0.0);

        var rot0 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 0);
        var rot1 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 1);
        var rot2 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 2);
        var rot3 = MapCoordHelper.hitVecToMapPixel(hit, FRAME, Direction.NORTH, 3);

        // 旋转应是双射：四个结果互不相同（除中心点外）
        assertTrue(rot0.u() != rot1.u() || rot0.v() != rot1.v(), "rot1 应不同于 rot0");
        assertTrue(rot1.u() != rot2.u() || rot1.v() != rot2.v(), "rot2 应不同于 rot1");
        assertTrue(rot2.u() != rot3.u() || rot2.v() != rot3.v(), "rot3 应不同于 rot2");

        // rot1 与 rot3 应中心对称
        assertEquals(1 - rot1.u(), rot3.u(), EPS);
        assertEquals(1 - rot1.v(), rot3.v(), EPS);
    }

    @Test
    @DisplayName("UV 始终落在 [0,1] 区间内")
    void hitVecToMapPixel_uvStaysWithinUnitRange() {
        for (Direction facing : Direction.values()) {
            for (int rot = 0; rot < 4; rot++) {
                for (double a = 0.0; a <= 1.0; a += 0.25) {
                    for (double b = 0.0; b <= 1.0; b += 0.25) {
                        var r = MapCoordHelper.hitVecToMapPixel(
                            new Vec3(a, b, a), FRAME, facing, rot);
                        assertTrue(r.u() >= -EPS && r.u() <= 1 + EPS,
                            "u 越界: facing=" + facing + " rot=" + rot + " u=" + r.u());
                        assertTrue(r.v() >= -EPS && r.v() <= 1 + EPS,
                            "v 越界: facing=" + facing + " rot=" + rot + " v=" + r.v());
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════
    // 地图中心网格对齐
    // ════════════════════════════════════════

    @ParameterizedTest(name = "playerX={0}, scale={1} → center={2}")
    @CsvSource({
        // scale 0：网格 128，中心偏移 64
        "0,     0, 64",
        "100,   0, 64",
        "128,   0, 192",
        "-1,    0, -64",
        // scale 1：网格 256，中心偏移 128
        "0,     1, 128",
        "300,   1, 384",
        // scale 4：网格 2048，中心偏移 1024
        "0,     4, 1024",
        "5000,  4, 5120"
    })
    @DisplayName("地图中心：按 128×2^scale 网格对齐并偏移半格")
    void calculateMapCenterCoord_alignsToGrid(double playerCoord, int scale, int expected) {
        assertEquals(expected, MapCoordHelper.calculateMapCenterCoord(playerCoord, scale));
    }

    @Test
    @DisplayName("地图中心：同一网格内的坐标得到相同中心")
    void calculateMapCenterCoord_sameGridYieldsSameCenter() {
        int center = MapCoordHelper.calculateMapCenterCoord(0, 0);
        // scale 0 网格为 [0,128)
        assertEquals(center, MapCoordHelper.calculateMapCenterCoord(1, 0));
        assertEquals(center, MapCoordHelper.calculateMapCenterCoord(127.9, 0));
        // 128 应进入下一网格
        assertTrue(MapCoordHelper.calculateMapCenterCoord(128, 0) != center);
    }

    @Test
    @DisplayName("地图中心：负坐标向下取整而非向零取整")
    void calculateMapCenterCoord_negativeCoordFloorsDownward() {
        // -1 属于网格 [-128,0)，中心为 -64
        assertEquals(-64, MapCoordHelper.calculateMapCenterCoord(-1, 0));
        // -128 属于网格 [-256,-128)... 边界值应落在 -128 起始的网格
        assertEquals(-64, MapCoordHelper.calculateMapCenterCoord(-0.5, 0));
    }

    // ════════════════════════════════════════
    // 射线到地图边界的距离
    // ════════════════════════════════════════

    @Test
    @DisplayName("边界距离：从中心向正东，距离为半个地图宽")
    void calcMaxDistToMapEdge_eastwardFromCenter() {
        // scale=1，地图 X 范围 [0-64, 0+64]
        double dist = MapCoordHelper.calcMaxDistToMapEdge(
            0, 0, 1, 0, 0, 0, 1);
        assertEquals(64.0, dist, EPS);
    }

    @Test
    @DisplayName("边界距离：方向向量为零时回退到 scale*64")
    void calcMaxDistToMapEdge_zeroDirectionFallsBack() {
        double dist = MapCoordHelper.calcMaxDistToMapEdge(
            0, 0, 0, 0, 0, 0, 3);
        assertEquals(3 * 64.0, dist, EPS,
            "无方向时应回退到 scale*64");
    }

    @Test
    @DisplayName("边界距离：对角方向取两轴较小值")
    void calcMaxDistToMapEdge_diagonalTakesMinimumAxis() {
        // 起点偏向东侧，X 轴先到边界
        double dist = MapCoordHelper.calcMaxDistToMapEdge(
            50, 0, 1, 1, 0, 0, 1);

        // X: (64-50)/1 = 14; Z: (64-0)/1 = 64 → 取 14
        assertEquals(14.0, dist, EPS);
    }

    @Test
    @DisplayName("边界距离：向西为负方向，仍返回正距离")
    void calcMaxDistToMapEdge_negativeDirectionReturnsPositive() {
        double dist = MapCoordHelper.calcMaxDistToMapEdge(
            0, 0, -1, 0, 0, 0, 1);
        assertTrue(dist > 0, "距离应为正，实际=" + dist);
        assertEquals(64.0, dist, EPS);
    }

    @Test
    @DisplayName("边界距离：起点已在边界外时回退到 scale*64")
    void calcMaxDistToMapEdge_originOutsideBoundsFallsBack() {
        // 起点在 X=200，地图范围 [-64,64]，向东走永远不会"到达"边界（已越过）
        double dist = MapCoordHelper.calcMaxDistToMapEdge(
            200, 0, 1, 0, 0, 0, 1);
        assertEquals(64.0, dist, EPS,
            "负距离应回退到 scale*64");
    }
}
