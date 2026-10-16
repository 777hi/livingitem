package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BeaconPyramid 纯逻辑测试（设计稿 §2.4：1D 金字塔判定）。
 *
 * <p>判据来源：原版方锥 4 层（3×3 / 5×5 / 7×7 / 9×9，累计 9/34/83/164）+
 * 用户 2026-10-15 定「每列 ≥ 目标、取满足的最高 N」。</p>
 */
class BeaconPyramidTest {

    // 各级精确目标柱高
    private static final long[] L1 = {0, 0, 0, 3, 3, 3, 0, 0, 0};
    private static final long[] L2 = {0, 0, 5, 8, 8, 8, 5, 0, 0};
    private static final long[] L3 = {0, 7, 12, 15, 15, 15, 12, 7, 0};
    private static final long[] L4 = {9, 16, 21, 24, 24, 24, 21, 16, 9};

    @Nested
    @DisplayName("判级（每列 ≥ 目标，取最高 N）")
    class ResolveLevel {

        @Test
        @DisplayName("四级精确目标 ⇒ 4")
        void exactL4() {
            assertEquals(4, BeaconPyramid.resolveLevel(L4));
        }

        @Test
        @DisplayName("三级精确目标 ⇒ 3")
        void exactL3() {
            assertEquals(3, BeaconPyramid.resolveLevel(L3));
        }

        @Test
        @DisplayName("二级精确目标 ⇒ 2")
        void exactL2() {
            assertEquals(2, BeaconPyramid.resolveLevel(L2));
        }

        @Test
        @DisplayName("一级精确目标（中 3 列各 3）⇒ 1")
        void exactL1() {
            assertEquals(1, BeaconPyramid.resolveLevel(L1));
        }

        @Test
        @DisplayName("全 0 ⇒ 0（未激活）")
        void allZero() {
            assertEquals(0, BeaconPyramid.resolveLevel(new long[9]));
        }

        @Test
        @DisplayName("null / 不足 9 长 ⇒ 0（不抛异常）")
        void nullOrShort() {
            assertEquals(0, BeaconPyramid.resolveLevel(null));
            assertEquals(0, BeaconPyramid.resolveLevel(new long[]{3, 3, 3}));
        }

        @Test
        @DisplayName("差一块即掉档：L4 边列少 1 ⇒ 3")
        void oneShortDropsLevel() {
            long[] h = L4.clone();
            h[0] = 8; // 边列目标 9，差 1
            assertEquals(3, BeaconPyramid.resolveLevel(h));
        }

        @Test
        @DisplayName("多堆不亏：全列灌满 ⇒ 仍 4")
        void extraBlocksNoHurt() {
            long[] h = {64, 64, 64, 64, 64, 64, 64, 64, 64};
            assertEquals(4, BeaconPyramid.resolveLevel(h));
        }

        @Test
        @DisplayName("嵌套单调：L4 柱高必然满足 L3/L2/L1")
        void nestedMonotonic() {
            assertTrue(BeaconPyramid.meets(L4, 3));
            assertTrue(BeaconPyramid.meets(L4, 2));
            assertTrue(BeaconPyramid.meets(L4, 1));
        }

        @Test
        @DisplayName("L3 中列只到 11 ⇒ 仍 2（其余列满足也不行）")
        void partialL3ColumnStaysL2() {
            long[] h = {0, 7, 12, 11, 15, 15, 12, 7, 0};
            assertEquals(2, BeaconPyramid.resolveLevel(h));
        }

        @Test
        @DisplayName("边界钳制：信标偏 1 列 ⇒ 最左 4 列全 0 ⇒ 最高 3（L3 中 7 列仍满足）")
        void clippedEdgeCapsLevel() {
            long[] h = {0, 16, 21, 24, 24, 24, 21, 16, 9};
            assertEquals(3, BeaconPyramid.resolveLevel(h));
        }

        @Test
        @DisplayName("最左列 3 ⇒ 仍 0（L1 要求中 3 列各 ≥3，缺 2 列）")
        void singleColumnCannotActivate() {
            long[] h = {3, 0, 0, 0, 0, 0, 0, 0, 0};
            assertEquals(0, BeaconPyramid.resolveLevel(h));
        }

        @Test
        @DisplayName("越界列视 0：L1 柱高但参与列被钳光 ⇒ 0")
        void clippedCannotActivate() {
            // 模拟信标在容器列 0：中心两侧 4 列中左侧全越界 ⇒ L1 的 ±1 也越界
            long[] h = {0, 0, 0, 0, 3, 3, 3, 0, 0};
            assertEquals(0, BeaconPyramid.resolveLevel(h));
        }
    }

    @Nested
    @DisplayName("差额（距下一级）")
    class Deficits {

        @Test
        @DisplayName("L0 柱高（中列 1）⇒ 距 L1 还差 8（3+2+3）")
        void deficitToL1() {
            long[] h = {0, 0, 0, 0, 1, 0, 0, 0, 0};
            assertEquals(8, BeaconPyramid.blocksToNext(0, h));
            assertArrayEquals(new int[]{0, 0, 0, 3, 2, 3, 0, 0, 0},
                BeaconPyramid.deficitsToNext(0, h));
        }

        @Test
        @DisplayName("满级 ⇒ 差额全 0")
        void atMaxLevel() {
            assertArrayEquals(new int[9], BeaconPyramid.deficitsToNext(4, L4));
            assertEquals(0, BeaconPyramid.blocksToNext(4, L4));
        }

        @Test
        @DisplayName("L3 ⇒ 距 L4 缺口 = 边列 9+16+21+21+16+9 − 已有")
        void deficitL3ToL4() {
            assertEquals(164 - 83, BeaconPyramid.blocksToNext(3, L3));
        }
    }

    @Nested
    @DisplayName("派生量（照原版公式）")
    class Derived {

        @Test
        @DisplayName("范围 = 等级×10+10")
        void range() {
            assertEquals(10.0, BeaconPyramid.rangeBlocks(0));
            assertEquals(20.0, BeaconPyramid.rangeBlocks(1));
            assertEquals(50.0, BeaconPyramid.rangeBlocks(4));
        }

        @Test
        @DisplayName("各级合计 = 0/9/34/83/164（与原版一致）")
        void totals() {
            assertEquals(0, BeaconPyramid.totalForLevel(0));
            assertEquals(9, BeaconPyramid.totalForLevel(1));
            assertEquals(34, BeaconPyramid.totalForLevel(2));
            assertEquals(83, BeaconPyramid.totalForLevel(3));
            assertEquals(164, BeaconPyramid.totalForLevel(4));
        }

        @Test
        @DisplayName("meets 越界等级 ⇒ false")
        void meetsOutOfRange() {
            assertFalse(BeaconPyramid.meets(L4, 0));
            assertFalse(BeaconPyramid.meets(L4, 5));
        }
    }
}
