package com.qiqi.li.living.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 飞艇底面对齐的纯坐标回归。 */
class SableTeleportMathTest {

    @Test
    @DisplayName("艇底高于原点：上移到底面目标，原点保留同一相对偏移")
    void alignsBottomWhenBottomAboveOrigin() {
        assertEquals(93.0,
            SableTeleportMath.originYForBottom(100.0, 107.0, 100.0),
            1.0E-9);
    }

    @Test
    @DisplayName("艇底低于原点：下移到底面目标，不使用只能升高的旧规则")
    void alignsBottomWhenBottomBelowOrigin() {
        assertEquals(117.0,
            SableTeleportMath.originYForBottom(120.0, 115.0, 112.0),
            1.0E-9);
    }

    @Test
    @DisplayName("平移后艇底严格等于目标高度")
    void translatedBottomEqualsTarget() {
        double origin = -37.25;
        double bottom = -42.75;
        double target = 64.0;
        double newOrigin = SableTeleportMath.originYForBottom(origin, bottom, target);
        double translatedBottom = bottom + (newOrigin - origin);

        assertEquals(target, translatedBottom, 1.0E-9);
    }
}
