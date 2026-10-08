package com.qiqi.li.client.util;

/**
 * 活箱子标签页的客户端开关态。
 *
 * <p>缓存「活箱子标签页是否激活」的静态标志，供 GUI 渲染判断是否需要展示活箱子的收纳内容预览。</p>
 */
public class LivingChestTabState {

    private static boolean active = false;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }
}