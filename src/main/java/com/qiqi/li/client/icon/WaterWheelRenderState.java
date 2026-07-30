package com.qiqi.li.client.icon;

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