package com.qiqi.li.living.domain.runtime;

import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 活物品运行时数据域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>本域**没有** {@link com.qiqi.li.living.api.LivingItemFunction}：它是「tooltip 运行时数据管道」
 * （构造 → 缓存 → 同步 → 渲染，见 {@link LivingItemRuntimeData} 类注释），
 * 因此这里只登记 static 缓存清理。</p>
 */
public final class RuntimeRegistration {

    private RuntimeRegistration() {}

    public static void register() {
        // ── static 缓存清理（登记点归属见 StaticCacheRegistry 类注释）──
        // 运行时遥测缓存：跨存档不清会把上个存档的 tooltip 数据带到新世界
        StaticCacheRegistry.onClientLogout(LivingItemClientCache::clear);
    }
}
