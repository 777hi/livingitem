package com.qiqi.li.living.domain.tools;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 活工具 / 活武器域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>活【工具】与活【武器】共用 {@link LivingToolFunction}（行为差异在 tick 里按记忆类型分派），
 * 因此这里只注册一个 Function。
 */
public final class ToolRegistration {

    private ToolRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingToolFunction());

        // ── static 缓存清理（登记点归属见 StaticCacheRegistry 类注释）──
        // 活工具的 FakePlayer 缓存（L26）：维度+主人 keyed，跨存档必须清
        StaticCacheRegistry.onServerStop(s -> LivingToolFakePlayerCache.clear());
        // 活工具容器同步（K2）：每个玩家的「上次发出内容」，跨存档必须清
        StaticCacheRegistry.onServerStop(s -> LivingToolHostSync.clear());
    }
}
