package com.qiqi.li.living.container;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        // 本类新增用例会往 ContainerRuntimeCache 写数据（治「作废键打空」的回归），
        // 一并清掉，避免串到其它测试类（clearAllCaches 不覆盖 L2 的运行时缓存）。
        ContainerRuntimeCache.clear();
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
        assertEquals("k", inv.get(0).cacheKey());
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

    /**
     * 回归（2026-10-12）：{@code removeDataByPos}（拆箱子 / 区块卸载）登记的作废键
     * 必须与 {@code ContainerRuntimeCache} 的存储键同构。
     *
     * <p>修复前三方键不一致：{@code POS_TO_CACHE_KEY} 取出的是「维度|坐标」缓存键，
     * 而 {@code trackLivingSlots} 与运行时缓存用的是裸 {@code chest_…} 键 ⇒
     * {@code LIVING_SLOT_ITEMS.remove} 与 {@code removeContainer} 全部落空
     * ⇒ 「容器整体卸载 ⇒ 作废整个运行时缓存」登记即打空（拆掉原地放回，旧燃料复活）。</p>
     */
    @Test
    @DisplayName("整容器作废：removeDataByPos 登记的键 == 运行时缓存存储键（拆箱子清得掉）")
    void containerRemoval_invalidationKey_matchesRuntimeCacheKey() {
        BlockPos pos = new BlockPos(64, 70, -8);
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        var ctx = new SimpleContainerContext(new net.neoforged.neoforge.items.ItemStackHandler(27),
            null, List.of(pos), List.of(), level);
        String cacheKey = ctx.getCacheKey();
        assertEquals("minecraft:overworld|chest_64_70_-8", cacheKey);

        // 预置一条运行时数据（模拟箱子里有个烧着的活熔炉）
        ContainerRuntimeCache.update(cacheKey, 3, RuntimeSegments.EMPTY);

        // 建位置索引（与 tick 路径同源：容器级数据首次创建时 indexPositions）
        ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerFluidData.KEY);

        // 拆箱子
        ContainerLivingItemHandler.removeDataByPos(level, pos);

        // L4 侧的消费循环（直译 LivingItem.onServerTick 的三行）
        for (var invalidation : ContainerLivingItemHandler.drainRuntimeCacheInvalidations()) {
            assertTrue(invalidation.wholeContainer(), "拆箱子 ⇒ 整容器作废");
            assertEquals(cacheKey, invalidation.cacheKey(),
                "作废键必须与运行时缓存存储键同构，否则 removeContainer 打空");
            ContainerRuntimeCache.removeContainer(invalidation.cacheKey());
        }

        assertEquals(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(cacheKey, 3),
            "作废生效后该槽位应回到 EMPTY（旧燃料/进度不复活）");
    }
}
