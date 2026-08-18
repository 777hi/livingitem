package com.qiqi.li.client.icon;

public class TorchRenderState {

    private static final ThreadLocal<Integer> ROTATION = new ThreadLocal<>();

    public static void setRotation(int degrees) {
        ROTATION.set(degrees);
    }

    public static int getRotation() {
        Integer r = ROTATION.get();
        return r != null ? r : 0;
    }

    public static void clear() {
        ROTATION.remove();
    }
}