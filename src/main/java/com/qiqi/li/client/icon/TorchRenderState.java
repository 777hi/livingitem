package com.qiqi.li.client.icon;

/**
 * 火把渲染态（线程局部）。
 *
 * <p>缓存当前火把的渲染旋转角度（{@code ThreadLocal}），供客户端渲染线程在绘制时读取；
 * 每帧结束后应调用 {@link #clear()} 释放，避免跨帧串扰。</p>
 */
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