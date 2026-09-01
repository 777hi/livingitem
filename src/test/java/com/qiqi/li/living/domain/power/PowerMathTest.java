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
}