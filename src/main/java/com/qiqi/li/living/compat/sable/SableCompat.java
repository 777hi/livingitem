package com.qiqi.li.living.compat.sable;

import net.neoforged.fml.ModList;

/**
 * Sable 模组兼容探测器。
 *
 * <p>缓存 {@code sable} 模组是否已加载（{@link #isLoaded}），避免重复查询
 * {@link net.neoforged.fml.ModList}，供其他模块在运行时判断是否需要走 Sable 集成路径。</p>
 */
public final class SableCompat {

    private static Boolean loaded;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("sable");
        }
        return loaded;
    }

    private SableCompat() {}
}