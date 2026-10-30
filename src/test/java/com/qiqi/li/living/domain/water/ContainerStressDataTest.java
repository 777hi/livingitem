package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ContainerStressData#wheelTorque} 的行为（2026-10-31 抽出纯函数后补测）。
 *
 * <p>为什么需要：水车宿主是 {@code create:water_wheel}，测试环境没有 Create
 * ⇒ {@code isLivingWaterWheel} 恒 false ⇒ 原先的 {@code calculate} 完全不可测。
 * 抽成「输入 level 表 → 输出 cw/ccw」的纯函数后，力矩数学与方向来源都能单测。</p>
 */
class ContainerStressDataTest {

    /**
     * 邻居 → level 查询器。测试里整容器只有一种流体 ⇒ 所有邻居共用同一张表。
     * 越界 / 无水 = -1。
     */
    private static IntFunction<IntUnaryOperator> levels(int... table) {
        return nb -> s -> s >= 0 && s < table.length ? table[s] : -1;
    }

    @Test
    @DisplayName("① 直流水无力矩：水车两侧都是径向水流（叉积为 0）")
    void straightFlow_hasNoTorque() {
        // 1×9：源在 0，水车在 4，3 / 5 是两侧水格
        int[] t = ContainerStressData.wheelTorque(4, 9, 9,
            levels(0, 1, 2, 3, -1, 5, 6, 7, 8), 1);

        assertEquals(0, t[0], "CW = 0：水流正对水车流过");
        assertEquals(0, t[1], "CCW = 0");
    }

    @Test
    @DisplayName("② 侧向水流产生 CCW：水车左侧的水朝下流（叉积为负）")
    void sideFlow_producesCcw() {
        // 3×3：上方 1 左右水位相等 ⇒ 力场为 0；左侧 3 的上方 0 更近源 ⇒ 3 的力场向下
        int[] t = ContainerStressData.wheelTorque(4, 3, 9,
            levels(0, 1, 0, 1, -1, -1, -1, -1, -1), 1);

        assertEquals(0, t[0], "CW = 0（上方那格力场为 0）");
        assertEquals(1, t[1], "CCW = 1（level 1 ⇒ 强度 7/7，叉积 -1）");
    }

    @Test
    @DisplayName("③ 堆叠放大：力矩 × 数量（SU 容量随堆叠放大，转速不变）")
    void stackSize_scalesTorque() {
        int[] t = ContainerStressData.wheelTorque(4, 3, 9,
            levels(0, 1, 0, 1, -1, -1, -1, -1, -1), 3);

        assertEquals(0, t[0], "CW = 0");
        assertEquals(3, t[1], "CCW = 3（1 × 3 个）");
    }

    @Test
    @DisplayName("④ 无水车邻居：力场平衡 ⇒ 0/0")
    void noWater_returnsZero() {
        int[] t = ContainerStressData.wheelTorque(4, 3, 9, levels(-1, -1, -1, -1, -1, -1, -1, -1, -1), 1);

        assertEquals(0, t[0]);
        assertEquals(0, t[1]);
    }
}
