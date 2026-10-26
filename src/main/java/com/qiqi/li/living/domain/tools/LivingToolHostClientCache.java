package com.qiqi.li.living.domain.tools;

import java.util.List;

import com.qiqi.li.living.util.DimensionScopedCache;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端「哪些容器里有活工具」缓存（{@code K2}）。
 *
 * <p>由 {@link LivingToolHostPacket#handle} 更新，供 {@code LivingToolRayRenderer}（射线可视化）
 * 与 {@code LivingToolModelRenderer}（悬浮模型 + 动画）读取。</p>
 *
 * <p>「整体替换 + 维度校验」两条不变量见 {@link DimensionScopedCache} —— 本类只保留类型，
 * 不再各自持有字段（四条渲染通道共用同一份实现）。</p>
 */
public final class LivingToolHostClientCache {

    private static final DimensionScopedCache<LivingToolHostPacket.Entry> CACHE =
        new DimensionScopedCache<>();

    private LivingToolHostClientCache() {
    }

    public static void update(ResourceLocation dim, List<LivingToolHostPacket.Entry> list) {
        CACHE.update(dim, list);
    }

    /**
     * 当前维度下的条目。
     *
     * @param currentDim 客户端当前所在维度
     * @return 条目列表；维度不匹配时返回空列表
     */
    public static List<LivingToolHostPacket.Entry> get(ResourceLocation currentDim) {
        return CACHE.get(currentDim);
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        CACHE.clear();
    }
}
