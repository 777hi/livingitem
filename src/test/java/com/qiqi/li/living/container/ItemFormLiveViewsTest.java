package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 物品形态容器的「活视图」登记表（2026-10-28）。
 *
 * <p>存在理由：物品形态没有世界位置，路由表按位置找不到它 ⇒ 按容器 UUID 登记本 tick 的
 * 内容视图。边界：背包不 tick 时登记过期（TTL），停服清空。</p>
 */
class ItemFormLiveViewsTest {

    @AfterEach
    void tearDown() {
        ItemFormLiveViews.clear();
    }

    @Test
    @DisplayName("登记后可按 UUID 取到同一活视图")
    void register_thenFind_returnsSameHandler() {
        UUID uuid = UUID.randomUUID();
        ItemStackHandler handler = new ItemStackHandler(27);

        ItemFormLiveViews.register(uuid, handler, null);

        assertSame(handler, ItemFormLiveViews.find(uuid));
        assertSame(handler, ItemFormLiveViews.find(uuid), "重复取应稳定（供提取端每 tick 使用）");
    }

    @Test
    @DisplayName("未登记 / 清空后返回 null（停服清空、背包没在 tick）")
    void unknownOrCleared_returnsNull() {
        UUID uuid = UUID.randomUUID();
        assertNull(ItemFormLiveViews.find(uuid));

        ItemFormLiveViews.register(uuid, new ItemStackHandler(9), null);
        ItemFormLiveViews.clear();

        assertNull(ItemFormLiveViews.find(uuid));
    }

    @Test
    @DisplayName("null 输入安全：register / find 都不抛")
    void nullSafety() {
        ItemFormLiveViews.register(null, new ItemStackHandler(9), null);
        assertNull(ItemFormLiveViews.find(null));
    }

    /**
     * 回归（2026-11-01，R-06 缺口 4）：惰性过期只在<b>有人查询</b>时回收。
     * 背包停止 tick 且再无人按该 UUID 取货（物品被销毁 / 丢失 / 玩家下线）⇒ 永远没人查
     * ⇒ 条目连同第三方 {@code IItemHandler} 的强引用一起常驻到停服。
     * {@link ItemFormLiveViews#evictExpired} 由容器层 sweep 按周期主动淘汰，补掉这一族。
     *
     * <p>{@code register} 内部用真实钟盖章，所以用例分两次调用测两端：
     * 用「判定时钟 = 现在」证明新鲜的不会被收，用「判定时钟推后 60s（&gt; TTL 2s）」证明过期的会被收。</p>
     */
    @Test
    @DisplayName("无人查询也要能收：evictExpired 主动淘汰过期条目")
    void evictExpired_removesStaleEntriesWithoutAnyQuery() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemStackHandler ha = new ItemStackHandler(27);
        ItemStackHandler hb = new ItemStackHandler(9);
        long now = System.currentTimeMillis();

        ItemFormLiveViews.register(a, ha, null);
        ItemFormLiveViews.register(b, hb, null);

        assertEquals(0, ItemFormLiveViews.evictExpired(now), "刚登记的（在 TTL 内）不该被淘汰");
        assertSame(ha, ItemFormLiveViews.find(a));
        assertSame(hb, ItemFormLiveViews.find(b));

        assertEquals(2, ItemFormLiveViews.evictExpired(now + 60_000),
            "越过 TTL 的两条应被主动淘汰（修复前：没人 find 就永远留在表里）");
        assertNull(ItemFormLiveViews.find(a));
        assertNull(ItemFormLiveViews.find(b));
    }
}
