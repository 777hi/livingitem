package com.qiqi.li.living.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 环动画客户端配置的默认值 / 限幅 / 热替换守卫。 */
class ToolVisualConfigTest {

    @AfterEach
    void reset() {
        ToolVisualConfig.resetForTest();
    }

    @Test
    @DisplayName("spec 构建 + 未加载时读默认值（与改造前的渲染器常量逐位一致）")
    void specBuildsAndDefaultsMatchLegacyConstants() {
        assertNotNull(ToolVisualConfig.SPEC);
        assertEquals("living_item-tool-visual-client.toml", ToolVisualConfig.FILE_NAME);
        assertEquals(6, ToolVisualConfig.pulseTicks());
        assertEquals(6, ToolVisualConfig.attackRingTicks());
        assertEquals(0.30F, ToolVisualConfig.pulseScale(), 1.0E-6F);
        assertEquals(4, ToolVisualConfig.spinPeriodMin());
        assertEquals(20, ToolVisualConfig.spinPeriodMax());
        assertEquals(10.0F, ToolVisualConfig.spinPeriodDivisor(), 1.0E-6F);
        assertEquals(8, ToolVisualConfig.returnTicks());
        assertEquals(1.77F, ToolVisualConfig.ringRadiusMax(), 1.0E-6F);
        assertEquals(21.0F, ToolVisualConfig.ringRadiusHalfSaturation(), 1.0E-6F);
    }

    @Test
    @DisplayName("脏 toml 值限幅 + 非有限值回退默认")
    void sanitizeClampsAndFallsBack() {
        assertEquals(0.0F, ToolVisualConfig.sanitizeDouble(-5, 0.30, 0.0, 2.0), 1.0E-6F);
        assertEquals(2.0F, ToolVisualConfig.sanitizeDouble(9, 0.30, 0.0, 2.0), 1.0E-6F);
        assertEquals(0.30F, ToolVisualConfig.sanitizeDouble(Double.NaN, 0.30, 0.0, 2.0), 1.0E-6F);
        assertEquals(0.30F,
            ToolVisualConfig.sanitizeDouble(Double.POSITIVE_INFINITY, 0.30, 0.0, 2.0), 1.0E-6F);
        assertEquals(0.5F,
            ToolVisualConfig.sanitizeDouble(0.1, 21.0, 0.5, 200.0), 1.0E-6F);
    }

    @Test
    @DisplayName("热替换：所有 getter 读到新值，无需重建渲染器")
    void applyForTestRefreshesAllGetters() {
        ToolVisualConfig.applyForTest(12, 20, 0.8F, 6, 30, 5.0F, 15, 2.5F, 40.0F);

        assertEquals(12, ToolVisualConfig.pulseTicks());
        assertEquals(20, ToolVisualConfig.attackRingTicks());
        assertEquals(0.8F, ToolVisualConfig.pulseScale(), 1.0E-6F);
        assertEquals(6, ToolVisualConfig.spinPeriodMin());
        assertEquals(30, ToolVisualConfig.spinPeriodMax());
        assertEquals(5.0F, ToolVisualConfig.spinPeriodDivisor(), 1.0E-6F);
        assertEquals(15, ToolVisualConfig.returnTicks());
        assertEquals(2.5F, ToolVisualConfig.ringRadiusMax(), 1.0E-6F);
        assertEquals(40.0F, ToolVisualConfig.ringRadiusHalfSaturation(), 1.0E-6F);

        assertDoesNotThrow(() -> ToolVisualConfig.resetForTest());
        assertEquals(6, ToolVisualConfig.pulseTicks());
    }
}
