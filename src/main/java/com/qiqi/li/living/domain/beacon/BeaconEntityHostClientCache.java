package com.qiqi.li.living.domain.beacon;

import java.util.List;

import com.qiqi.li.living.util.DimensionScopedCache;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「哪些实体宿主身上有活信标」缓存。
 *
 * <p>由 {@link BeaconEntityHostPacket#handle} 更新，供 {@code LivingBeamRenderer}
 * 给实体宿主画光束。整体替换 + 维度校验两条不变量见 {@link DimensionScopedCache}。</p>
 */
public final class BeaconEntityHostClientCache {

    private static final DimensionScopedCache<BeaconEntityHostPacket.Entry> CACHE =
        new DimensionScopedCache<>();

    private BeaconEntityHostClientCache() {
    }

    public static void update(ResourceLocation dim, List<BeaconEntityHostPacket.Entry> list) {
        CACHE.update(dim, list);
    }

    /** 当前维度下的条目（维度不匹配返回空列表）。 */
    public static List<BeaconEntityHostPacket.Entry> get(ResourceLocation currentDim) {
        return CACHE.get(currentDim);
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        CACHE.clear();
    }
}
