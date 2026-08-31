package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红电数学工具测试 —— 公式 v2：(log₂|Δ| × n)^(1+u) × P。
 */
class PowerMathTest {

    private static final double EPS = 1e-9;
    private static final int HIGH = 4096;  // log₂=12

    @Test
    @DisplayName("耦合管径：未锈蚀 1.0 → 氧化 0.35，越界 clamp")
    void coupling_followsRustLadder() {
        assertEquals(1.0, PowerMath.coupling(0), EPS);
        assertEquals(0.7, PowerMath.coupling(1), EPS);
        assertEquals(0.5, PowerMath.coupling(2), EPS);
        assertEquals(0.35, PowerMath.coupling(3), EPS);
        assertEquals(1.0, PowerMath.coupling(-5), EPS);
        assertEquals(0.35, PowerMath.coupling(9), EPS);
    }

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
    @DisplayName("合因子 = (log₂|Δ| × n)^(1+u)：地板 12n，天花板 (12n)²")
    void combinedFactor_unlockExponent() {
        assertEquals(12.0, PowerMath.combinedFactor(HIGH, 1, 0.0), EPS);
        assertEquals(144.0, PowerMath.combinedFactor(HIGH, 1, 1.0), EPS);
        assertEquals(60.0, PowerMath.combinedFactor(HIGH, 5, 0.0), EPS);
        assertEquals(3600.0, PowerMath.combinedFactor(HIGH, 5, 1.0), EPS);
        assertEquals(Math.pow(60, 1.5), PowerMath.combinedFactor(HIGH, 5, 0.5), 1e-9);
        assertEquals(0.0, PowerMath.combinedFactor(HIGH, 0, 1.0), EPS);
    }

    @Test
    @DisplayName("RE → FE 边界换算 K = 1/16")
    void reToFe_matchesOriginalCalibration() {
        assertEquals(0, PowerMath.reToFe(0));
        assertEquals(16, PowerMath.reToFe(256));
        assertEquals(32000, PowerMath.reToFe(512000));
    }
}