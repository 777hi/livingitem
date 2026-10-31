package com.qiqi.li.living.container;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R-06 缓存生命周期回收（2026-11-01）—— 守「容器不再被 tick 看到 ⇒ 数据要被收」这条不变量。
 *
 * <p><b>病</b>：跨 tick 表原先只有四条清理路径（容器继续 tick 且已空 / 玩家破坏 /
 * 物品形态取消活化 / 停服），而容器停止被处理的成因有六种（区块卸载、玩家下线、实体消失、
 * 物品销毁、掉落物被拾取、非玩家移除方块）—— 六条都没被覆盖，数据一路留到停服。
 * 逐成因挂事件要六条路径且必漏其一，因此改判「消失满阈值」这个<b>共同结果</b>
 * （见 {@code docs/reference/container-data-lifecycle.md} §4 与 {@code D-container-20}：
 * 运行时数据瞬态可丢，所以可以激进地收）。</p>
 */
class ContainerCacheSweepTest {

    private static final long TTL = ContainerLivingItemHandler.UNSEEN_TTL_MS;

    @AfterEach
    void tearDown() {
        ContainerLivingItemHandler.clearAllCaches();
        ContainerRuntimeCache.clear();
    }

    private static Level overworld() {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    private static SimpleContainerContext blockContainer(Level level, BlockPos... positions) {
        return new SimpleContainerContext(new ItemStackHandler(27), null,
            List.of(positions), List.of(), level);
    }

    // ── 主路径：lastSeen sweep ────────────────────────────────

    @Test
    @DisplayName("消失满阈值的容器被回收：四张表 + 整容器运行时作废（与「玩家破坏」同一条入口）")
    void unseenContainer_isReclaimed() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(10, 64, 10));
        String key = ctx.getCacheKey();

        ContainerLivingItemHandler.trackLivingSlots(key, Map.of(3, Items.FURNACE), ctx);
        ContainerRuntimeCache.update(ctx, 3, RuntimeSegments.EMPTY);
        ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertEquals(1, ContainerLivingItemHandler.trackedContainerCount());

        // 停止被看到：把盖章时刻推到阈值之外（真实运行里由「容器不再进入 processContext」造成）
        long now = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(key, now - TTL - 1);

        assertEquals(1, ContainerLivingItemHandler.sweepUnseenContainers(now, TTL), "应回收 1 个容器");
        assertEquals(0, ContainerLivingItemHandler.trackedContainerCount(), "槽位追踪应被清");

        var inv = ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertEquals(1, inv.size(), "回收应登记整容器作废");
        assertTrue(inv.get(0).wholeContainer(), "作废粒度 = 整容器");
        assertEquals(key, inv.get(0).cacheKey(), "作废键必须与运行时缓存存储键同构");

        ContainerRuntimeCache.removeContainer(inv.get(0).cacheKey());
        assertFalse(ContainerRuntimeCache.holdsContainer(key), "L4 消费后运行时缓存不该再有这个键");
    }

    @Test
    @DisplayName("阈值内回来的容器不被动 —— 走远再回来不该看到进度被清")
    void containerBackWithinTtl_isKept() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(1, 64, 1));
        String key = ctx.getCacheKey();
        ContainerLivingItemHandler.trackLivingSlots(key, Map.of(0, Items.FURNACE), ctx);

        long now = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(key, now - TTL / 2);

        assertEquals(0, ContainerLivingItemHandler.sweepUnseenContainers(now, TTL));
        assertEquals(1, ContainerLivingItemHandler.trackedContainerCount());
        assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty(),
            "未过期 ⇒ 不该产生任何作废登记");
    }

    @Test
    @DisplayName("每 tick 重新盖章 ⇒ 持续被处理的容器在真实时钟下不会被 sweep 收走")
    void stillTickingContainer_neverExpires() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(2, 64, 2));
        String key = ctx.getCacheKey();

        // 模拟「容器持续被 tick」：每 tick 盖章（盖章用真实钟），
        // 于是按真实「现在」判定的 sweep 永远看不到过期的章 ⇒ 正在用的容器不会被误清。
        for (int tick = 1; tick <= 3; tick++) {
            ContainerLivingItemHandler.trackLivingSlots(key, Map.of(5, Items.FURNACE), ctx);
            assertEquals(0, ContainerLivingItemHandler.sweepUnseenContainers(System.currentTimeMillis(), TTL),
                "第 " + tick + " 次扫描不该收掉刚盖章的容器");
        }
        assertEquals(1, ContainerLivingItemHandler.trackedContainerCount());
    }

    // ── 缺口 1：只装活物品的容器也要有位置索引 ──────────────────

    @Test
    @DisplayName("只装活物品（无容器级数据）的容器：破坏方块即刻清，不再等 sweep")
    void livingOnlyContainer_getsPositionIndexOnFirstTracking() {
        Level level = overworld();
        BlockPos pos = new BlockPos(7, 70, -3);
        SimpleContainerContext ctx = blockContainer(level, pos);
        String key = ctx.getCacheKey();

        // 故意不调 getOrCreateContainerData / entry(ctx) —— 这正是原缺口的成因
        ContainerLivingItemHandler.trackLivingSlots(key, Map.of(1, Items.FURNACE), ctx);
        assertEquals(1, ContainerLivingItemHandler.trackedContainerCount());

        ContainerLivingItemHandler.removeDataByPos(level, pos);

        assertEquals(0, ContainerLivingItemHandler.trackedContainerCount(),
            "位置索引命中 ⇒ 破坏应清掉槽位追踪（修复前这里恒为 1：索引压根没建）");
        var inv = ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertEquals(1, inv.size(), "并且登记整容器运行时作废");
        assertEquals(key, inv.get(0).cacheKey());
    }

    @Test
    @DisplayName("双箱：任一半的位置命中即清整个键（数据可重建，不做「另一半还在」的保留逻辑）")
    void doubleChest_eitherHalfClearsWholeKey() {
        Level level = overworld();
        BlockPos left = new BlockPos(20, 65, 5);
        BlockPos right = new BlockPos(21, 65, 5);
        SimpleContainerContext ctx = blockContainer(level, left, right);
        String key = ctx.getCacheKey();

        ContainerLivingItemHandler.trackLivingSlots(key, Map.of(0, Items.FURNACE), ctx);
        ContainerLivingItemHandler.removeDataByPos(level, right);

        assertEquals(0, ContainerLivingItemHandler.trackedContainerCount(),
            "按右半坐标破坏也要清掉整条双箱数据（键本身是一个整体）");
        assertFalse(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty());
    }

    @Test
    @DisplayName("无上下文（测试替身 / 无世界）时不建索引，也不抛")
    void nullContext_isSafeAndStillStamps() {
        ContainerLivingItemHandler.trackLivingSlots("bare_key", Map.of(0, Items.FURNACE), null);
        assertEquals(1, ContainerLivingItemHandler.trackedContainerCount());
        long now = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest("bare_key", now - TTL - 1);
        assertEquals(1, ContainerLivingItemHandler.sweepUnseenContainers(now, TTL),
            "没有位置索引也照样能被 sweep 收掉（这是它作为兜底路径的意义）");
    }

    // ── 缺口 3：removeSlot 删空内层后要删外层键 ──────────────────

    @Test
    @DisplayName("最后一个槽位被作废后，运行时缓存不留空壳外层键")
    void removeSlot_lastSlot_deletesOuterKey() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(9, 60, 9));
        String key = ctx.getCacheKey();

        ContainerRuntimeCache.update(ctx, 2, RuntimeSegments.EMPTY);
        assertTrue(ContainerRuntimeCache.holdsContainer(key), "写入后应有外层键");

        ContainerRuntimeCache.removeSlot(key, ctx.toPhysicalSlot(2));
        assertFalse(ContainerRuntimeCache.holdsContainer(key),
            "删掉最后一个槽位 ⇒ 外层键应一并消失（修复前留下空壳，只能等整容器作废或停服）");
    }

    @Test
    @DisplayName("删中间槽位不影响其它槽位，也不误删外层键")
    void removeSlot_keepsOtherSlots() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(9, 61, 9));
        String key = ctx.getCacheKey();
        ContainerRuntimeCache.update(ctx, 2, RuntimeSegments.EMPTY);
        ContainerRuntimeCache.update(ctx, 4, RuntimeSegments.EMPTY);

        ContainerRuntimeCache.removeSlot(key, ctx.toPhysicalSlot(2));

        assertTrue(ContainerRuntimeCache.holdsContainer(key), "还有其它槽位 ⇒ 外层键要保留");
        assertEquals(Map.of(ctx.toPhysicalSlot(4), RuntimeSegments.EMPTY),
            ContainerRuntimeCache.getSnapshot(key));
    }

    // ── 可观测性（实机验证 sweep 是否在回收的入口） ──────────────

    @Test
    @DisplayName("describeLifecycleStats 报出四张表的规模，供 /livingitem debug cache 观察是否回落")
    void describeLifecycleStats_reportsTableSizes() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(3, 64, 3));
        ContainerLivingItemHandler.trackLivingSlots(ctx.getCacheKey(), Map.of(0, Items.FURNACE), ctx);

        String line = ContainerLivingItemHandler.describeLifecycleStats();

        assertTrue(line.contains("追踪容器=1"), line);
        assertTrue(line.contains("盖章键=1"), line);
        assertTrue(line.contains("位置索引=1"), line + "（只装活物品的容器也该有位置索引 = J2 的补建）");
    }

    @Test
    @DisplayName("sweep 之后盖章键归零 —— 实机「规模不涨」判据的最小版")
    void describeLifecycleStats_returnsToZeroAfterSweep() {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(4, 64, 4));
        String key = ctx.getCacheKey();
        ContainerLivingItemHandler.trackLivingSlots(key, Map.of(0, Items.FURNACE), ctx);

        long now = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(key, now - TTL - 1);
        assertEquals(1, ContainerLivingItemHandler.sweepUnseenContainers(now, TTL));
        ContainerLivingItemHandler.drainRuntimeCacheInvalidations();

        assertTrue(ContainerLivingItemHandler.describeLifecycleStats().contains("盖章键=0"),
            ContainerLivingItemHandler.describeLifecycleStats());
        assertEquals(0, ContainerLivingItemHandler.trackedContainerCount());
    }
}
