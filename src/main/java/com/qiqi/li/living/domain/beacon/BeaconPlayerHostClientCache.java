package com.qiqi.li.living.domain.beacon;

import java.util.List;

import com.qiqi.li.living.util.DimensionScopedCache;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「哪些玩家背包里有活信标」缓存。
 *
 * <p>由 {@link BeaconPlayerHostPacket#handle} 更新，供 {@code LivingBeamRenderer}
 * 给其它玩家画垂直光束。</p>
 *
 * <p>「整体替换 + 维度校验」两条不变量见 {@link DimensionScopedCache} —— 本类只保留类型，
 * 不再各自持有字段。</p>
 */
public final class BeaconPlayerHostClientCache {

    private static final DimensionScopedCache<BeaconPlayerHostPacket.Entry> CACHE =
        new DimensionScopedCache<>();

    private BeaconPlayerHostClientCache() {
    }

    public static void update(ResourceLocation dim, List<BeaconPlayerHostPacket.Entry> list) {
        CACHE.update(dim, list);
    }

    /** 当前维度下的条目（维度不匹配返回空列表）。 */
    public static List<BeaconPlayerHostPacket.Entry> get(ResourceLocation currentDim) {
        return CACHE.get(currentDim);
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        CACHE.clear();
    }
}
