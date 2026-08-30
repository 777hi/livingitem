package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红电数学工具测试 —— 公式来源 docs/红电系统.md §3.4（v17.4）。
 */
class PowerMathTest {

    private static final double EPS = 1e-9;

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
        // |4−8|=2 → θ=(2/4)×360°=180°? 不——θ 用偏好周期：2/8? 此处用 P=4 误差 1 → 90°
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
    @DisplayName("合因子 = n^(1+解锁度)：地板 n，天花板 n²")
    void combinedFactor_unlockExponent() {
        assertEquals(1.0, PowerMath.combinedFactor(1, 0.0), EPS);
        assertEquals(1.0, PowerMath.combinedFactor(1, 1.0), EPS);   // 单路调谐无感
        assertEquals(5.0, PowerMath.combinedFactor(5, 0.0), EPS);
        assertEquals(25.0, PowerMath.combinedFactor(5, 1.0), EPS);
        assertEquals(Math.pow(5, 1.5), PowerMath.combinedFactor(5, 0.5), 1e-9);
        assertEquals(0.0, PowerMath.combinedFactor(0, 1.0), EPS);   // 无效 n
    }

    @Test
    @DisplayName("RE → FE 边界换算 K = 1/16")
    void reToFe_matchesOriginalCalibration() {
        assertEquals(0, PowerMath.reToFe(0));
        assertEquals(16, PowerMath.reToFe(256));
        assertEquals(32000, PowerMath.reToFe(512000));
    }
}
