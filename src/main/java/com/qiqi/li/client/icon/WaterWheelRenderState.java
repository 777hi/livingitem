package com.qiqi.li.client.icon;

import net.minecraft.Util;

/**
 * 水车渲染态（线程局部）。
 *
 * <p>缓存当前水车的转速 RPM（{@code ThreadLocal}），供 {@link RotatingWaterWheelModel} 等渲染器读取
 * 以决定旋转角度；每帧结束后应调用 {@link #clear()} 释放，避免跨帧串扰。</p>
 */
public class WaterWheelRenderState {

    private static final ThreadLocal<Float> RPM = new ThreadLocal<>();

    public static void setRPM(float rpm) {
        RPM.set(rpm);
    }

    public static float getRpm() {
        Float rpm = RPM.get();
        return rpm != null ? rpm : 0f;
    }

    public static void clear() {
        RPM.remove();
    }

    /**
     * 图标旋转相位（度，符号随 rpm）。
     *
     * <p>⚠️ 时间源必须走本方法（客户端单调时钟 + 全程 double），禁止改锚
     * {@code level.getGameTime()}——它每秒被服务器时间同步包硬覆盖，且 float 大数取模
     * 有精度台阶（老存档图标一卡一卡的根因，2026-10-29）。速度沿用 Create 口径：
     * 1 RPM = 0.3°/tick = 6°/s。</p>
     */
    public static float spinAngleDeg(float rpm) {
        return spinAngleDeg(rpm, Util.getMillis());
    }

    /** 可注入时钟的纯函数（单测用）。 */
    static float spinAngleDeg(float rpm, long nowMillis) {
        if (rpm == 0f) return 0f;
        return (float) (nowMillis * (rpm * (360.0 / 60_000.0)) % 360.0);
    }
}