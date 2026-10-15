package com.qiqi.li.living.domain.beacon;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「哪些玩家背包里有活信标」缓存（照 {@code LivingToolHostClientCache} 模式）。
 */
public final class BeaconPlayerHostClientCache {

    private static volatile ResourceLocation dimension;
    private static volatile List<BeaconPlayerHostPacket.Entry> entries = List.of();

    private BeaconPlayerHostClientCache() {
    }

    public static void update(ResourceLocation dim, List<BeaconPlayerHostPacket.Entry> list) {
        dimension = dim;
        entries = List.copyOf(list);
    }

    /** 当前维度下的条目（维度不匹配返回空列表）。 */
    public static List<BeaconPlayerHostPacket.Entry> get(ResourceLocation currentDim) {
        return currentDim != null && currentDim.equals(dimension) ? entries : List.of();
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        dimension = null;
        entries = List.of();
    }
}
