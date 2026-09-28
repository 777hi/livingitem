package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.api.LivingItemManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * 活水域注册入口（活水桶 / 活水车）—— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
@ApiStatus.Internal
public final class WaterRegistration {

    private WaterRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingWaterBucketFunction());
        LivingItemManager.registerFunction(new LivingWaterWheelFunction());
    }
}
