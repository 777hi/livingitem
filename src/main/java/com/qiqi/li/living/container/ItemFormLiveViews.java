package com.qiqi.li.living.container;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 物品形态容器的「活视图」登记表（2026-10-28）。
 *
 * <p>物品形态容器没有世界位置 ⇒ 路由表 / 任何按位置寻址的消费方都找不到它。
 * 这里按容器 UUID 登记<b>本 tick 的</b>内容视图（{@code ItemFormContainers.Source#contentHandler}），
 * 供跨容器「按 UUID 寻址」使用（目前唯一消费者：活末影箱路由的提取端）。</p>
 *
 * <h3>成立条件（是边界，不是实现遗漏）</h3>
 * <ul>
 *   <li><b>背包必须在 tick</b>：登记由 {@code LivingBackpackFunction} 每 tick 刷新；
 *       背包被放进不 tick 的容器 / 玩家退出后，条目在 {@link #TTL_MS} 后失效 ⇒ 取不到货。</li>
 *   <li><b>不持久化</b>：重启后靠重新 tick 建立；停服清空。</li>
 *   <li><b>创造模式复制的背包共享 UUID</b> ⇒ 两个实例都登记、后写覆盖先写
 *       （与精妙背包本身「中键复制共享 UUID」同源，已知取舍）。</li>
 * </ul>
 */
public final class ItemFormLiveViews {

    /**
     * 超过这个时长没刷新就视为「容器不在了」。
     *
     * <p>物品形态容器每 tick（50ms）刷新一次；给 40 tick 余量以容忍服务端卡顿 ——
     * 取不到货的代价是「这一次提取跳过」，比误判成「容器还在」安全。</p>
     */
    private static final long TTL_MS = 2000L;

    private record Entry(IItemHandler handler, ResourceKey<Level> dim, long lastSeenMs) {}

    private static final Map<UUID, Entry> VIEWS = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(s -> clear());
    }

    private ItemFormLiveViews() {}

    /** 每 tick 上报（覆盖写）。{@code level} 允许 null（测试替身 / 非世界上下文）。 */
    public static void register(UUID containerUuid, IItemHandler handler, @Nullable Level level) {
        if (containerUuid == null || handler == null) return;
        ResourceKey<Level> dim = level == null ? null : level.dimension();
        VIEWS.put(containerUuid, new Entry(handler, dim, System.currentTimeMillis()));
    }

    /** 取当前可用的内容视图；没有登记或已过期返回 {@code null}。 */
    @Nullable
    public static IItemHandler find(@Nullable UUID containerUuid) {
        if (containerUuid == null) return null;
        Entry e = VIEWS.get(containerUuid);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.lastSeenMs() > TTL_MS) {
            VIEWS.remove(containerUuid);
            return null;
        }
        return e.handler();
    }

    /** 已登记的容器 UUID 是否仍活着（供注册闸门判断）。 */
    public static boolean isLive(@Nullable UUID containerUuid) {
        return find(containerUuid) != null;
    }

    public static void clear() {
        VIEWS.clear();
    }
}
