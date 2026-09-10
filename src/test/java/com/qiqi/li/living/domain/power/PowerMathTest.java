package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红电数学工具测试 —— 公式 v3：Σ√|Δ_i|，合因子 = (eff_δ_sum)^(1+u)。
 */
class PowerMathTest {

    private static final double EPS = 1e-9;
    // √4096 = 64（单路最大信号 |Δ| 的 eff_δ_sum）
    private static final double EFF_DELTA_SINGLE = Math.sqrt(4096);
    // 5 路 √4096 求和 = 320
    private static final double EFF_DELTA_FIVE = 5 * Math.sqrt(4096);

    @Test
    @DisplayName("调谐效率：完美匹配 1.0，差 1 tick@4t（90°）为 0.5，反相为 0")
    void tuningEfficiency_cosineCurve() {
        assertEquals(1.0, PowerMath.tuningEfficiency(0, 4), 1e-9);
        assertEquals(0.5, PowerMath.tuningEfficiency(1, 4), 1e-9);
        assertEquals(0.0, PowerMath.tuningEfficiency(2, 4), 1e-9);
        // 64t 差 1 tick → 5.6° → ~0.995
        assertTrue(PowerMath.tuningEfficiency(1, 64) > 0.99);
    }

    @Test
    @DisplayName("调谐效率：偏好周期 < 2（宽带）恒为 0")
    void tuningEfficiency_broadbandIsZero() {
        assertEquals(0.0, PowerMath.tuningEfficiency(0, 1), EPS);
        assertEquals(0.0, PowerMath.tuningEfficiency(0, 0), EPS);
    }

    @Test
    @DisplayName("合因子 = (eff_δ_sum)^(1+u)：地板 64¹=64，天花板 64²=4096")
    void combinedFactor_unlockExponent() {
        assertEquals(64.0, PowerMath.combinedFactor(EFF_DELTA_SINGLE, 1, 0.0), EPS);
        assertEquals(4096.0, PowerMath.combinedFactor(EFF_DELTA_SINGLE, 1, 1.0), EPS);
        assertEquals(320.0, PowerMath.combinedFactor(EFF_DELTA_FIVE, 5, 0.0), EPS);
        assertEquals(102400.0, PowerMath.combinedFactor(EFF_DELTA_FIVE, 5, 1.0), EPS);
        assertEquals(Math.pow(320, 1.5), PowerMath.combinedFactor(EFF_DELTA_FIVE, 5, 0.5), 1e-9);
        assertEquals(0.0, PowerMath.combinedFactor(EFF_DELTA_SINGLE, 0, 1.0), EPS);
    }

    @Test
    @DisplayName("RE → FE 边界换算 K = 1/16")
    void reToFe_matchesOriginalCalibration() {
        assertEquals(0, PowerMath.reToFe(0));
        assertEquals(16, PowerMath.reToFe(256));
        assertEquals(32000, PowerMath.reToFe(512000));
    }

    @Test
    @DisplayName("mulDivFloor：小量级下必须与朴素整数除法逐位一致（double 不引入任何偏差）")
    void mulDivFloor_exactAtSmallMagnitudes() {
        assertEquals(250, PowerMath.mulDivFloor(1000, 500, 2000));
        assertEquals(3, PowerMath.mulDivFloor(10, 3, 10));
        assertEquals(0, PowerMath.mulDivFloor(1, 1, 3));
        assertEquals(1_600_000, PowerMath.mulDivFloor(1_600_000, 1_600_000, 1_600_000));

        // 性质扫描：量级 < 2^53 时不允许出现任何一处偏差
        for (long a = 0; a <= 1000; a += 97) {
            for (long b = 1; b <= 1000; b += 89) {
                long r = PowerMath.mulDivFloor(a, b, 1000);
                assertEquals((a * b) / 1000, r, "a=" + a + " b=" + b);
                assertTrue(r <= b, "份额不得超过比例因子 b");
            }
        }
    }

    @Test
    @DisplayName("mulDivFloor：非正入参一律返回 0")
    void mulDivFloor_nonPositiveIsZero() {
        assertEquals(0, PowerMath.mulDivFloor(0, 100, 100));
        assertEquals(0, PowerMath.mulDivFloor(100, 0, 100));
        assertEquals(0, PowerMath.mulDivFloor(100, 100, 0));
        assertEquals(0, PowerMath.mulDivFloor(-1, 100, 100));
        assertEquals(0, PowerMath.mulDivFloor(100, -1, 100));
    }

    @Test
    @DisplayName("mulDivFloor：定点量级下不溢出（朴素写法 a×b 已达 3.2e19 ≫ Long.MAX 9.22e18）")
    void mulDivFloor_survivesFixedPointMagnitudes() {
        long totalRemaining = PowerMath.BULB_UNIT_CAPACITY_MFE * 64L * 27L;   // 1.728e12
        long remaining = PowerMath.BULB_UNIT_CAPACITY_MFE * 64L;             // 6.4e10

        // 请求量 ≥ 总容量：份额 = 整堆剩余容量（朴素 a*b = 1.1e23，必然溢出）
        assertEquals(remaining,
            PowerMath.mulDivFloor(totalRemaining, remaining, totalRemaining));

        // 外部请求 50 万 FE（Flux 接入点的典型量级）：朴素 a*b = 3.2e19 已越过 Long.MAX。
        // 修复前这里返回乱值 → 分配量≈0 → leftover=accept → 零头回收退化成 ~10 亿轮。
        long want = 500_000L * 1000L;
        assertEquals(18_518_518L, PowerMath.mulDivFloor(want, remaining, totalRemaining));
    }

    @Test
    @DisplayName("mulDivFloor：a > c（发电量超过容器剩余容量）按 a = c 处理，份额恒不超过 b")
    void mulDivFloor_clampsOversizedNumerator() {
        assertEquals(100, PowerMath.mulDivFloor(2_000_000_000_000L, 100L, 1_000_000_000_000L));
        assertTrue(PowerMath.mulDivFloor(Long.MAX_VALUE, 7L, 1L) <= 7L);
    }
}