package com.qiqi.li.living.domain.furnace;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活熔炉域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class FurnaceRegistration {

    private FurnaceRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingFurnaceFunction());
    }
}
