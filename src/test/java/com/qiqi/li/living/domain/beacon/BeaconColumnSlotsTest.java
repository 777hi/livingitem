package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 下方 9 列槽位解析（纯槽位数学）测试 —— 与 {@code BeaconPyramidTest}（柱高判定）互补。
 *
 * <p>容器口径：27 槽 = 3 行 × 9 列（行 0 = 槽 0-8，行 1 = 9-17，行 2 = 18-26）。
 * 信标本列 = 下标 {@link BeaconPyramid#CENTER}（4）；越界列 = −1（柱高按 0 计）。</p>
 */
class BeaconColumnSlotsTest {

    private static final int SIZE = 27;
    private static final int WIDTH = 9;

    @Test
    @DisplayName("信标在行 1 中列（槽 13）⇒ 下方 = 行 2 全 9 槽（18-26）")
    void centered() {
        assertArrayEquals(
            new int[]{18, 19, 20, 21, 22, 23, 24, 25, 26},
            BeaconFunction.resolveColumnSlots(13, SIZE, WIDTH));
    }

    @Test
    @DisplayName("信标在行 0 右列（槽 8）⇒ 右侧 4 列越界（-1）")
    void rightEdgeClipped() {
        assertArrayEquals(
            new int[]{13, 14, 15, 16, 17, -1, -1, -1, -1},
            BeaconFunction.resolveColumnSlots(8, SIZE, WIDTH));
    }

    @Test
    @DisplayName("信标在行 0 左列（槽 0）⇒ 左侧 4 列越界（-1）")
    void leftEdgeClipped() {
        assertArrayEquals(
            new int[]{-1, -1, -1, -1, 9, 10, 11, 12, 13},
            BeaconFunction.resolveColumnSlots(0, SIZE, WIDTH));
    }

    @Test
    @DisplayName("信标在最底行（槽 22）⇒ 下方无行，全 −1（无法激活）")
    void bottomRow() {
        int[] targets = BeaconFunction.resolveColumnSlots(22, SIZE, WIDTH);
        for (int t : targets) {
            assertEquals(-1, t);
        }
    }

    @Test
    @DisplayName("信标在行 0 中列（槽 4）⇒ 下方 = 行 1 全 9 槽（9-17）")
    void topRowCentered() {
        assertArrayEquals(
            new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17},
            BeaconFunction.resolveColumnSlots(4, SIZE, WIDTH));
    }

    @Test
    @DisplayName("小容器（9 槽单行）⇒ 下方无行，全 −1")
    void singleRowContainer() {
        int[] targets = BeaconFunction.resolveColumnSlots(4, 9, 9);
        for (int t : targets) {
            assertEquals(-1, t);
        }
    }
}
