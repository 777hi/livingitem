package com.qiqi.li.living.container;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时缓存生命周期（方案 A，2026-10-11）单测 —— 槽位追踪与作废登记的判定。
 *
 * <p><b>守的规则</b>：物品离开槽位 / 换成另一种物品 ⇒ 登记作废该槽位的运行时缓存；
 * 同类物品持续存在 ⇒ 不登记。</p>
 *
 * <p>⚠️ 判据<b>只看物品类型、不看组件</b> —— 燃烧标志等组件会被 tick 改写，
 * 若纳入判据会把正在运行的缓存误清（燃料 / 进度每次状态翻转就丢）。</p>
 *
 * <p>L4 侧「取走登记 → 调 {@code ContainerRuntimeCache} 清理」的循环是直译的 3 行，
 * 由游戏内验证覆盖（见 living-brewing-stand-tech / living-furnace-tech 的验证清单）。</p>
 */
class ContainerRuntimeCacheLifecycleTest {

    @AfterEach
    void tearDown() {
        // 项目红线：静态状态必须显式 reset（Gradle 同一 JVM 跑全部测试）
        ContainerLivingItemHandler.clearAllCaches();
    }

    @Test
    void itemLeavingSlot_isInvalidated() {
        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE));
        assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty(),
            "首次追踪（无历史）不该产生作废");

        ContainerLivingItemHandler.trackLivingSlots("k", Map.<Integer, Item>of());
        List<ContainerLivingItemHandler.RuntimeCacheInvalidation> inv =
            ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertEquals(1, inv.size(), "槽位空了 ⇒ 应登记 1 条作废");
        assertEquals("k", inv.get(0).containerKey());
        assertTrue(inv.get(0).slots().contains(3));
        assertFalse(inv.get(0).wholeContainer());
    }

    @Test
    void swappingItemType_isInvalidated() {
        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE));
        ContainerLivingItemHandler.drainRuntimeCacheInvalidations();

        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.HOPPER));
        List<ContainerLivingItemHandler.RuntimeCacheInvalidation> inv =
            ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertEquals(1, inv.size(), "换成另一种活物品 ⇒ 应登记作废");
        assertTrue(inv.get(0).slots().contains(3));
    }

    @Test
    void sameItemPersisting_isNotInvalidated() {
        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE));
        ContainerLivingItemHandler.drainRuntimeCacheInvalidations();

        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE));
        assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty(),
            "同类物品持续存在 ⇒ 不该作废（否则燃料/进度会被反复清零）");
    }

    @Test
    void drainIsDestructive_secondCallEmpty() {
        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE));
        ContainerLivingItemHandler.trackLivingSlots("k", Map.<Integer, Item>of());
        assertEquals(1, ContainerLivingItemHandler.drainRuntimeCacheInvalidations().size());
        assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty(),
            "取走即清空 ⇒ 第二次 drain 应为空");
    }

    @Test
    void nullContainerKey_isIgnored() {
        ContainerLivingItemHandler.trackLivingSlots(null, Map.of(3, Items.FURNACE));
        assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty());
    }
}
