package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 活末影箱域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class EnderRegistration {

    private EnderRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingEnderChestFunction());
        // static 缓存清理 —— 登记点归属见 StaticCacheRegistry 类注释（领域缓存由领域自己登记）
        StaticCacheRegistry.onServerStop(s -> EnderChannelRegistry.getInstance().clearAll());
    }
}
