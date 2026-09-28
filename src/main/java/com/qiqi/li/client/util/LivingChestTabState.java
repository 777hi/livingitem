package com.qiqi.li.client.util;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class LivingChestTabState {

    private static boolean active = false;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }
}