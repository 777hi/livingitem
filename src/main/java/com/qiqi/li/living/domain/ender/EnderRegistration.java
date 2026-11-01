package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活末影箱域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class EnderRegistration {

    private EnderRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingEnderChestFunction());
        // 框架中继（2026-10-08：transfer 不再认识本领域）—— 同 ChestRegistration
        com.qiqi.li.living.transfer.SlotAccessorFactory.registerProvider(LivingEnderChestAccessor::tryCreate);
        com.qiqi.li.living.transfer.ContainerLikeItems.register(LivingEnderChestFunction::isLivingEnderChest);
        // 传输目标策略：末影箱路由接管容器内 / 跨容器两种目标分派
        com.qiqi.li.living.transfer.TransferTargetPolicies.register(new EnderTransferPolicy());
        // 写回阶段路由维护（GC 单钩子，取代漏斗/末影箱镜像双调用）
        com.qiqi.li.living.container.ContainerTickHooks.register(new EnderRouteMaintenance());
        // 注：static 缓存清理已改由缓存类**自己**登记（2026-10-12）——
        // 见 EnderChannelRegistry / EnderChannelClientCache 的静态块与 StaticCacheRegistry 类注释
    }
}
