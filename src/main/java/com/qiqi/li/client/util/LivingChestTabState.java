package com.qiqi.li.client.util;

public class LivingChestTabState {

    private static boolean active = false;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }
}