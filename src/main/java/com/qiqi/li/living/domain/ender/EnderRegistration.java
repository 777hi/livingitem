package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活末影箱域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class EnderRegistration {

    private EnderRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingEnderChestFunction());
    }
}
