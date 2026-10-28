package com.qiqi.li.living.container;

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
}
