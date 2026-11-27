package com.qiqi.li.living.container;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时缓存生命周期单测 —— 槽位追踪与整容器同步清理。
 *
 * <p><b>守的规则</b>：物品离开槽位 / 换成另一种物品 ⇒ 标记该槽位失效；
 * 同类物品持续存在 ⇒ 保留。</p>
 *
 * <p>⚠️ 判据<b>只看物品类型、不看组件</b> —— 燃烧标志等组件会被 tick 改写，
 * 若纳入判据会把正在运行的缓存误清（燃料 / 进度每次状态翻转就丢）。</p>
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
        assertTrue(ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE), null).isEmpty(),
            "首次追踪（无历史）不该产生作废");
        assertEquals(Set.of(3), ContainerLivingItemHandler.trackLivingSlots("k", Map.of(), null));
    }

    @Test
    void swappingItemType_isInvalidated() {
        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE, 4, Items.HOPPER), null);
        assertEquals(Set.of(3), ContainerLivingItemHandler.trackLivingSlots(
            "k", Map.of(3, Items.HOPPER, 4, Items.HOPPER), null));
    }

    @Test
    void sameItemPersisting_isNotInvalidated() {
        ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE), null);
        assertTrue(ContainerLivingItemHandler.trackLivingSlots("k", Map.of(3, Items.FURNACE), null).isEmpty(),
            "同类物品持续存在 ⇒ 不该作废（否则燃料/进度会被反复清零）");
    }

    @Test
    void repeatedRemoval_doesNotTouchAnotherKey() {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        var removed = blockContainer(level, new BlockPos(64, 70, -8));
        var kept = blockContainer(level, new BlockPos(65, 70, -8));
        String removedKey = removed.getCacheKey();
        String keptKey = kept.getCacheKey();
        ContainerRuntimeCache.update(removed, 3, RuntimeSegments.EMPTY);
        ContainerRuntimeCache.update(kept, 3, RuntimeSegments.EMPTY);
        assertTrue(ContainerRuntimeCache.holdsContainer(removedKey));
        assertTrue(ContainerRuntimeCache.holdsContainer(keptKey));

        ContainerLivingItemHandler.removeDataByKey(removedKey);
        assertFalse(ContainerRuntimeCache.holdsContainer(removedKey), "首次删除必须立即清除指定键");
        assertTrue(ContainerRuntimeCache.holdsContainer(keptKey), "首次删除不影响另一键");

        ContainerLivingItemHandler.removeDataByKey(removedKey);
        assertFalse(ContainerRuntimeCache.holdsContainer(removedKey));
        assertTrue(ContainerRuntimeCache.holdsContainer(keptKey), "重复删除不影响另一键");
    }

    @Test
    void nullContainerKey_isIgnored() {
        assertTrue(ContainerLivingItemHandler.trackLivingSlots(null, Map.of(3, Items.FURNACE), null).isEmpty());
    }

    /** 按位置删除必须使用和运行时缓存相同的带维度键。 */
    @Test
    @DisplayName("整容器作废：按位置拆箱子立即清除带维度的运行时键")
    void containerRemoval_invalidationKey_matchesRuntimeCacheKey() {
        BlockPos pos = new BlockPos(64, 70, -8);
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        var ctx = blockContainer(level, pos);
        String cacheKey = ctx.getCacheKey();
        assertEquals("minecraft:overworld|chest_64_70_-8", cacheKey);

        // 预置一条运行时数据（模拟箱子里有个烧着的活熔炉）
        ContainerRuntimeCache.update(ctx, 3, RuntimeSegments.EMPTY);
        assertTrue(ContainerRuntimeCache.holdsContainer(cacheKey), "预置数据必须写到预期缓存键");

        // 建位置索引（与 tick 路径同源：容器级数据首次创建时 indexPositions）
        ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerFluidData.KEY);

        // 拆箱子
        ContainerLivingItemHandler.removeDataByPos(level, pos);

        assertFalse(ContainerRuntimeCache.holdsContainer(cacheKey),
            "按位置删除返回前必须清掉整键，不能保留旧燃料或进度");
    }

    private static SimpleContainerContext blockContainer(Level level, BlockPos pos) {
        return new SimpleContainerContext(new ItemStackHandler(27), null, List.of(pos), List.of(), level);
    }
}
