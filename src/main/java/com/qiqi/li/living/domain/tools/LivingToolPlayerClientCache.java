package com.qiqi.li.living.domain.tools;

import java.util.List;
import java.util.UUID;

import com.qiqi.li.living.util.DimensionScopedCache;
import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端「附近玩家背包里的活工具」缓存（联机可见性 · 最小版）。
 *
 * <p>由 {@link LivingToolPlayerPacket#handle} 更新，供 {@code LivingToolModelRenderer}
 * 给<b>其它玩家</b>画背后的待机环 / 攻击环 / 主动工具体。</p>
 *
 * <p>「整体替换 + 维度校验」两条不变量见 {@link DimensionScopedCache} —— 本类只保留类型
 * 与两个按玩家查找的便捷方法。</p>
 */
public final class LivingToolPlayerClientCache {

    private static final DimensionScopedCache<LivingToolPlayerPacket.Entry> CACHE =
        new DimensionScopedCache<>();

    static {
        // static 缓存清理：登记与 clear() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        // ⚠️ 2026-10-12 补：此前 clear() **零调用者** ⇒ 跨存档会把上个存档的活工具残留渲染出来
        StaticCacheRegistry.onClientLogout(LivingToolPlayerClientCache::clear);
    }

    private LivingToolPlayerClientCache() {
    }

    public static void update(ResourceLocation dim, List<LivingToolPlayerPacket.Entry> list) {
        CACHE.update(dim, list);
    }

    /**
     * 当前维度下，「其它玩家 → 他们的活工具」条目。
     *
     * @param currentDim 客户端当前所在维度
     * @return 条目列表；维度不匹配时返回空列表
     */
    public static List<LivingToolPlayerPacket.Entry> get(ResourceLocation currentDim) {
        return CACHE.get(currentDim);
    }

    /** 另取一份（渲染遍历时避免和 volatile 读交错）。 */
    public static List<LivingToolPlayerPacket.Entry> snapshot() {
        return CACHE.snapshot();
    }

    /** 按玩家查找；没有则返回 {@code null}。 */
    public static List<ItemStack> toolsOf(UUID playerId) {
        for (LivingToolPlayerPacket.Entry entry : CACHE.snapshot()) {
            if (entry.playerId().equals(playerId)) {
                return entry.tools();
            }
        }
        return null;
    }

    /** 清空（客户端退出存档时调用，避免跨存档残留）。 */
    public static void clear() {
        CACHE.clear();
    }
}
