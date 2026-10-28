package com.qiqi.li.client.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 水车图标旋转时钟回归（2026-10-29 换源：gameTime → Util.getMillis()）。
 *
 * <p>旧实现 {@code (gameTime + partialTick) * rpm * 0.3f % 360f} 全程 float：gameTime 每秒被
 * 服务器时间同步包硬覆盖（相位跳变），gameTime 数百万 tick 后 float 精度台阶让动画逐帧跳格
 * （老存档图标「一卡一卡」的根因）。本类钉住新时钟的三条契约：<b>速度</b>（Create 口径
 * 1 RPM = 0.3°/tick = 6°/s）、<b>方向</b>（符号随 rpm）、<b>大时钟不退化</b>（全程 double，
 * 任意时钟量级下步进精度不变）。</p>
 */
class WaterWheelRenderStateTest {

    @Test
    @DisplayName("速度：8 RPM = 48°/s（Create 口径 0.3°/tick × 20 tick/s）")
    void speed_eightRpm() {
        assertEquals(0f, WaterWheelRenderState.spinAngleDeg(8f, 0L), 1e-3f);
        assertEquals(48f, WaterWheelRenderState.spinAngleDeg(8f, 1_000L), 1e-3f, "1 秒走 48°");
        assertEquals(180f, WaterWheelRenderState.spinAngleDeg(8f, 3_750L), 1e-3f, "半圈 3.75 秒");
    }

    @Test
    @DisplayName("方向：符号随 rpm（负 rpm 反转）")
    void direction_followsRpmSign() {
        assertEquals(-48f, WaterWheelRenderState.spinAngleDeg(-8f, 1_000L), 1e-3f);
    }

    @Test
    @DisplayName("静止：rpm = 0 恒 0（任意时钟值）")
    void zeroRpm_still() {
        assertEquals(0f, WaterWheelRenderState.spinAngleDeg(0f, 0L), 0f);
        assertEquals(0f, WaterWheelRenderState.spinAngleDeg(0f, Long.MAX_VALUE), 0f);
    }

    @Test
    @DisplayName("回绕连续：一圈 7.5 秒，跨 360° 回绕处步进依旧平滑")
    void wraps_continuously() {
        assertEquals(0f, WaterWheelRenderState.spinAngleDeg(8f, 7_500L), 1e-3f, "整圈回到 0");
        assertEquals(0.048f, WaterWheelRenderState.spinAngleDeg(8f, 7_501L), 1e-3f,
            "回绕后第 1 毫秒 = 正常步进，不是 360° 跳变");
    }

    @Test
    @DisplayName("大时钟不退化：约 41 天时钟量级下每秒仍走准 48°")
    void largeClock_noQuantization() {
        long t = 3_600_000_000L;
        assertEquals(0f, WaterWheelRenderState.spinAngleDeg(8f, t), 1e-3f);
        assertEquals(48f, WaterWheelRenderState.spinAngleDeg(8f, t + 1_000L), 1e-3f,
            "全程 double ⇒ 任意时钟量级下速度不变（float 旧实现此处已是大步进台阶）");
    }
}
