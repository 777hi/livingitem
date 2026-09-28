package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 红电（电力层）域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class PowerRegistration {

    private PowerRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingWaxedCopperFunction());
    }
}
