package com.qiqi.li.client.icon;

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
}