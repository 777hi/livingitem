package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 9 柱小金字塔面板几何纯函数测试（设计稿 §2.6.2）——「像素对不上」类 bug 在此层拦掉，
 * 不靠启动游戏截图。
 */
class BeaconPanelGeometryTest {

    private static boolean[] exists(boolean... values) {
        return values;
    }

    private static final boolean[] ALL_EXIST = new boolean[9];
    static {
        Arrays.fill(ALL_EXIST, true);
    }

    @Test
    @DisplayName("满程 24 = L4 中心列目标（契约防漂移）")
    void maxHeightMatchesL4Center() {
        assertEquals(24, BeaconPanelGeometry.MAX_H);
        assertEquals(24, BeaconPyramid.targetAt(3, BeaconPyramid.CENTER));
    }

    @Test
    @DisplayName("柱位 x：起点 8、槽宽 16、末列不溢面板")
    void columnXSpacing() {
        assertEquals(8, BeaconPanelGeometry.columnX(0));
        assertEquals(8 + 4 * 16, BeaconPanelGeometry.columnX(BeaconPyramid.CENTER));
        assertEquals(8 + 8 * 16, BeaconPanelGeometry.columnX(8));
        assertTrue(BeaconPanelGeometry.columnX(8) + BeaconPanelGeometry.BAR_W
            <= BeaconPanelGeometry.PANEL_W);
    }

    @Test
    @DisplayName("刻度 y = 55 − round(target×40/24)：L1=50 / L2=42 / L3=30 / L4=15")
    void targetYValues() {
        assertEquals(50, BeaconPanelGeometry.targetY(3));
        assertEquals(42, BeaconPanelGeometry.targetY(8));
        assertEquals(30, BeaconPanelGeometry.targetY(15));
        assertEquals(15, BeaconPanelGeometry.targetY(24));
        assertEquals(55, BeaconPanelGeometry.targetY(0));
    }

    @Test
    @DisplayName("柱顶 y：0⇒基线、24⇒满程、溢出钳到满程（溢出量由帽+数字表达）")
    void plotYClampsAtMax() {
        assertEquals(55, BeaconPanelGeometry.plotY(0));
        assertEquals(42, BeaconPanelGeometry.plotY(8));
        assertEquals(15, BeaconPanelGeometry.plotY(24));
        assertEquals(15, BeaconPanelGeometry.plotY(32));
        assertEquals(15, BeaconPanelGeometry.plotY(64));
    }

    @Test
    @DisplayName("缺口高度：柱 8 目标 15 ⇒ 12px（柱顶 42 → 刻度 30）")
    void gapHeight() {
        assertEquals(12, BeaconPanelGeometry.gapHeight(8, 15));
        assertEquals(0, BeaconPanelGeometry.gapHeight(15, 15));
        assertEquals(0, BeaconPanelGeometry.gapHeight(20, 15));   // 超额达标
        assertEquals(0, BeaconPanelGeometry.gapHeight(8, 0));     // 非参与列
    }

    @Test
    @DisplayName("数字取舍：未达标⇒目标值；达标/超额/非参与⇒不画；溢出⇒绝对高度")
    void labels() {
        assertEquals(15, BeaconPanelGeometry.goalLabel(8, 15));
        assertEquals(-1, BeaconPanelGeometry.goalLabel(15, 15));
        assertEquals(-1, BeaconPanelGeometry.goalLabel(20, 15));  // 超额但不溢出 ⇒ 无数字
        assertEquals(-1, BeaconPanelGeometry.goalLabel(0, 0));    // 非参与列

        assertFalse(BeaconPanelGeometry.isOverflow(24));
        assertTrue(BeaconPanelGeometry.isOverflow(25));
        assertEquals(32L, BeaconPanelGeometry.overflowLabel(32));
        assertEquals(-1L, BeaconPanelGeometry.overflowLabel(24));
    }

    @Test
    @DisplayName("可达性：参与列全在⇒可达；参与列有越界⇒不可达；非参与列越界不影响")
    void reachable() {
        assertTrue(BeaconPanelGeometry.reachable(0, ALL_EXIST));   // L1 只中 3 列

        // 信标贴左缘：列 0/1 越界 ⇒ L1（中 3 列）/ L2（列 2..6）仍可达；
        // L3 起参与列含 1 ⇒ 不可达；L4 全 9 列参与更不可达
        var leftClipped = exists(false, false, true, true, true, true, true, true, true);
        assertTrue(BeaconPanelGeometry.reachable(0, leftClipped));
        assertTrue(BeaconPanelGeometry.reachable(1, leftClipped));
        assertFalse(BeaconPanelGeometry.reachable(2, leftClipped));
        assertFalse(BeaconPanelGeometry.reachable(3, leftClipped));

        // 信标在最左列：列 0..4 全越界 ⇒ L1 参与列 3/4 不存在 ⇒ 永远不可达
        var farLeft = exists(false, false, false, false, false, true, true, true, true);
        assertFalse(BeaconPanelGeometry.reachable(0, farLeft));

        // 空/短数组安全读取
        assertFalse(BeaconPanelGeometry.existsAt(null, 0));
        assertFalse(BeaconPanelGeometry.existsAt(new boolean[3], 8));
        assertTrue(BeaconPanelGeometry.existsAt(ALL_EXIST, 4));
    }

    @Test
    @DisplayName("columnExistsFrom：targets ≥ 0 ⇒ 存在；−1 ⇒ 不存在")
    void columnExistsFrom() {
        assertArrayEquals(exists(false, false, true, true, true, true, true, true, true),
            BeaconFunction.columnExistsFrom(new int[]{-1, -1, 9, 10, 11, 12, 13, 14, 15}));
        assertArrayEquals(new boolean[9],
            BeaconFunction.columnExistsFrom(new int[]{-1, -1, -1, -1, -1, -1, -1, -1, -1}));
        assertArrayEquals(ALL_EXIST,
            BeaconFunction.columnExistsFrom(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}));
    }
}
