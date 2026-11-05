package com.qiqi.li.living.container;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.power.ContainerPowerData;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.domain.sponge.SpongeContainerFluidHandler;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.mockStatic;
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
    private static final int COMPLETED_TICKS = 3600;

    private List<ContainerTickHook> savedHooks;

    @BeforeEach
    void isolateHooksAndCaches() {
        savedHooks = ContainerTickHooks.snapshotForTest();
        ContainerTickHooks.replaceForTest(List.of());
        ContainerLivingItemHandler.clearAllCaches();
        ContainerRuntimeCache.clear();
    }

    @AfterEach
    void tearDown() {
        ContainerTickHooks.replaceForTest(savedHooks);
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

    private static final class NoOpLivingFunction implements LivingItemFunction {
        @Override
        public boolean canApply(ItemStack stack) {
            return LivingItemManager.isLivingItem(stack);
        }

        @Override
        public String getFunctionId() {
            return "cache_sweep_test";
        }

        @Override
        public boolean shouldTickWithoutOwnItems(ContainerContext ctx) {
            return true;
        }

        @Override
        public void tick(List<SlotEntry> entries, ContainerContext ctx, TickContext tick, Level level) {
        }
    }

    private static final class EdgeHook implements ContainerTickHook {
        int starts;
        int writebacks;

        @Override
        public void onTickStart(ContainerContext ctx, TickContext tick) {
            starts++;
            SpongeContainerFluidHandler.tickEdge(ctx);
        }

        @Override
        public void onWriteback(TickableContainerContext ctx, TickContext tick) {
            writebacks++;
        }
    }

    /** 注册表只在当前作用域替换查询；真实活化判定与其余静态方法保持原行为。 */
    private static MockedStatic<LivingItemManager> isolateFunctions() {
        LivingItemFunction function = new NoOpLivingFunction();
        MockedStatic<LivingItemManager> registry = mockStatic(LivingItemManager.class, CALLS_REAL_METHODS);
        registry.when(() -> LivingItemManager.getApplicableFunctions(any(ItemStack.class)))
            .thenAnswer(invocation -> function.canApply(invocation.getArgument(0))
                ? List.of(function) : List.of());
        registry.when(LivingItemManager::getSelfSustainingFunctions).thenReturn(List.of(function));
        return registry;
    }

    private static long capabilityInvalidations(Level level, BlockPos pos) {
        return mockingDetails(level).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("invalidateCapabilities"))
            .filter(invocation -> invocation.getArguments().length == 1)
            .filter(invocation -> pos.equals(invocation.getArgument(0)))
            .count();
    }

    private static void assertLifecycleSizes(int tracked, int stamps, int positions, int invalidations) {
        String stats = ContainerLivingItemHandler.describeLifecycleStats();
        assertAll(
            () -> assertTrue(stats.contains("追踪容器=" + tracked + " "), stats),
            () -> assertTrue(stats.contains("盖章键=" + stamps + " "), stats),
            () -> assertTrue(stats.contains("位置索引=" + positions + " "), stats),
            () -> assertTrue(stats.endsWith("待作废=" + invalidations), stats));
    }

    @Test
    @DisplayName("真实 processContext 连续超过两次旧清理节拍：活海绵 EDGE 保持，能力仅在存在性翻转时作废")
    void processContext_spongeEdgeSurvivesTwoCleanupIntervals() {
        Level level = overworld();
        BlockPos pos = new BlockPos(30, 64, 30);
        ItemStackHandler inventory = new ItemStackHandler(27);
        SimpleContainerContext ctx = new SimpleContainerContext(inventory, null,
            List.of(pos), List.of(), level);
        ItemStack sponge = new ItemStack(Items.SPONGE);
        LivingItemManager.setLiving(sponge, true);
        inventory.setStackInSlot(0, sponge);
        EdgeHook hook = new EdgeHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        try (MockedStatic<LivingItemManager> ignored = isolateFunctions()) {
            for (int tick = 0; tick < COMPLETED_TICKS; tick++) {
                ContainerLivingItemHandler.processContext(ctx, level);
            }
            assertEquals(COMPLETED_TICKS, hook.starts, "每次调用必须实际进入 tick 开始钩子");
            assertEquals(COMPLETED_TICKS, hook.writebacks, "每次调用必须实际完成写回阶段");
            assertNull(ctx.getTickContext(), "完成后不留 TickContext");
            Boolean edgeWhilePresent = ctx.peekContainerData(SpongeContainerFluidHandler.EDGE);
            long invalidationsWhilePresent = capabilityInvalidations(level, pos);
            assertAll(
                () -> assertNull(ctx.peekContainerData(ContainerFluidData.KEY), "不得有水网保护条目"),
                () -> assertNull(ctx.peekContainerData(ContainerRedstoneData.KEY), "不得有红石保护条目"),
                () -> assertNull(ctx.peekContainerData(ContainerPowerData.KEY), "不得有电力保护条目"));

            inventory.setStackInSlot(0, ItemStack.EMPTY);
            ContainerLivingItemHandler.processContext(ctx, level);
            assertEquals(COMPLETED_TICKS + 1, hook.writebacks, "移除后空 tick 仍必须完成");
            ContainerLivingItemHandler.processContext(ctx, level);
            assertEquals(COMPLETED_TICKS + 2, hook.writebacks, "持续空 tick 不得重复通告下降沿");
            assertAll(
                () -> assertEquals(Boolean.TRUE, edgeWhilePresent, "活海绵存在时 EDGE 必须跨清理节拍保留"),
                () -> assertEquals(1L, invalidationsWhilePresent, "持续存在只通告一次能力"),
                () -> assertEquals(Boolean.FALSE, ctx.peekContainerData(SpongeContainerFluidHandler.EDGE),
                    "移除后的真实 hook 必须写回下降沿"),
                () -> assertEquals(2L, capabilityInvalidations(level, pos), "出现与移除各作废一次"));
        }
    }

    @Test
    @DisplayName("持续 tick 的活物品专用容器：超过两次旧清理节拍后位置索引仍能显式删除")
    void processContext_livingOnlyPositionIndexSurvivesTwoCleanupIntervals() {
        Level level = overworld();
        BlockPos pos = new BlockPos(31, 64, 31);
        ItemStackHandler inventory = new ItemStackHandler(27);
        SimpleContainerContext ctx = new SimpleContainerContext(inventory, null,
            List.of(pos), List.of(), level);
        ItemStack livingItem = new ItemStack(Items.FURNACE);
        LivingItemManager.setLiving(livingItem, true);
        inventory.setStackInSlot(0, livingItem);
        int[] completed = {0};
        ContainerTickHooks.replaceForTest(List.of(new ContainerTickHook() {
            @Override
            public void onWriteback(TickableContainerContext context, TickContext tick) {
                completed[0]++;
            }
        }));

        try (MockedStatic<LivingItemManager> ignored = isolateFunctions()) {
            for (int tick = 0; tick < COMPLETED_TICKS; tick++) {
                ContainerLivingItemHandler.processContext(ctx, level);
            }
        }
        assertEquals(COMPLETED_TICKS, completed[0], "所有 processContext 调用都必须完成主流程");
        assertEquals(1, ContainerLivingItemHandler.trackedContainerCount());
        String activeStats = ContainerLivingItemHandler.describeLifecycleStats();
        ContainerLivingItemHandler.removeDataByPos(level, pos);
        var invalidations = ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertAll(
            () -> assertTrue(activeStats.contains("位置索引=1 "), activeStats),
            () -> assertEquals(0, ContainerLivingItemHandler.trackedContainerCount(), "按位置删除必须命中活容器"),
            () -> assertEquals(List.of(new ContainerLivingItemHandler.RuntimeCacheInvalidation(
                ctx.getCacheKey(), java.util.Set.of(), true)), invalidations));
    }

    private enum EntryAccess {
        PEEK {
            @Override void access(SimpleContainerContext ctx) {
                ctx.peekContainerData(SpongeContainerFluidHandler.EDGE);
            }
        },
        GET_OR_CREATE {
            @Override void access(SimpleContainerContext ctx) {
                ctx.getOrCreateContainerData(SpongeContainerFluidHandler.EDGE);
            }
        },
        PUT {
            @Override void access(SimpleContainerContext ctx) {
                ContainerLivingItemHandler.putContainerData(ctx, SpongeContainerFluidHandler.EDGE, true);
            }
        },
        REVISION {
            @Override void access(SimpleContainerContext ctx) {
                ContainerLivingItemHandler.bumpContainerRevision(ctx);
            }
        },
        SNAPSHOT {
            @Override void access(SimpleContainerContext ctx) {
                ContainerLivingItemHandler.getCachedSnapshot(ctx,
                    ContainerLivingItemHandler.getContainerRevision(ctx), new TickContext(ctx));
            }
        };

        abstract void access(SimpleContainerContext ctx);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EntryAccess.class)
    @DisplayName("只经 entry 访问的容器先登记盖章和索引；重复查询不续期，注入截止时刻可回收")
    void queryOnlyEntry_registersOnceAndDoesNotRenewStamp(EntryAccess access) {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(32, 64, 32));
        access.access(ctx);
        assertLifecycleSizes(0, 1, 1, 0);

        long cutoff = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(ctx.getCacheKey(), cutoff - TTL + 1);
        assertEquals(0, ContainerLivingItemHandler.sweepUnseenContainers(cutoff, TTL),
            "回收阈值内保留查询创建的条目");
        ContainerLivingItemHandler.setLastSeenForTest(ctx.getCacheKey(), cutoff - TTL);
        access.access(ctx);
        assertEquals(1, ContainerLivingItemHandler.sweepUnseenContainers(cutoff, TTL),
            "访问已存在的条目不是 tick，不得更新盖章");
        var invalidations = ContainerLivingItemHandler.drainRuntimeCacheInvalidations();
        assertEquals(List.of(new ContainerLivingItemHandler.RuntimeCacheInvalidation(
            ctx.getCacheKey(), java.util.Set.of(), true)), invalidations);
        assertLifecycleSizes(0, 0, 0, 0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EntryAccess.class, names = {"PEEK", "REVISION"})
    @DisplayName("空条目与只有修订计数的条目都走统一 TTL 回收")
    void emptyOrRevisionOnlyEntry_isSwept(EntryAccess access) {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(33, 64, 33));
        access.access(ctx);
        long cutoff = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(ctx.getCacheKey(), cutoff - TTL - 1);

        assertEquals(1, ContainerLivingItemHandler.sweepUnseenContainers(cutoff, TTL));
        assertEquals(0L, ContainerLivingItemHandler.getContainerRevision(ctx), "修订计数随条目整体回收");
        assertEquals(1, ContainerLivingItemHandler.drainRuntimeCacheInvalidations().size());
        assertLifecycleSizes(0, 0, 0, 0);
    }

    private enum Removal {
        BY_KEY {
            @Override void remove(SimpleContainerContext ctx) {
                ContainerLivingItemHandler.removeDataByKey(ctx.getCacheKey());
            }
        },
        BY_POS {
            @Override void remove(SimpleContainerContext ctx) {
                ContainerLivingItemHandler.removeDataByPos(ctx.getLevel(), ctx.getAssociatedBlockPositions().get(0));
            }
        };

        abstract void remove(SimpleContainerContext ctx);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Removal.class)
    @DisplayName("显式删除同步清 LAST_SEEN，之后 TTL sweep 不重复发整容器作废")
    void explicitRemoval_clearsStampWithoutDuplicateSweepInvalidation(Removal removal) {
        Level level = overworld();
        SimpleContainerContext ctx = blockContainer(level, new BlockPos(34, 64, 34));
        ContainerLivingItemHandler.trackLivingSlots(ctx.getCacheKey(), Map.of(0, Items.FURNACE), ctx);
        ContainerLivingItemHandler.putContainerData(ctx, SpongeContainerFluidHandler.EDGE, true);
        long cutoff = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(ctx.getCacheKey(), cutoff - TTL - 1);

        removal.remove(ctx);
        assertEquals(List.of(new ContainerLivingItemHandler.RuntimeCacheInvalidation(
            ctx.getCacheKey(), java.util.Set.of(), true)),
            ContainerLivingItemHandler.drainRuntimeCacheInvalidations());
        String removedStats = ContainerLivingItemHandler.describeLifecycleStats();
        int swept = ContainerLivingItemHandler.sweepUnseenContainers(cutoff, TTL);
        assertAll(
            () -> assertTrue(removedStats.contains("盖章键=0 "), removedStats),
            () -> assertEquals(0, swept, "显式删除的键不再进入 TTL 回收"),
            () -> assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty(),
                "同一删除不得重复登记整容器作废"));
        assertLifecycleSizes(0, 0, 0, 0);
    }

    @Test
    @DisplayName("同键删除后重建：取得新盖章和新索引，不继承旧过期章")
    void sameKeyRecreation_hasFreshStampAndPositionIndex() {
        Level level = overworld();
        BlockPos pos = new BlockPos(35, 64, 35);
        SimpleContainerContext original = blockContainer(level, pos);
        ContainerLivingItemHandler.trackLivingSlots(original.getCacheKey(), Map.of(0, Items.FURNACE), original);
        long cutoff = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(original.getCacheKey(), cutoff - TTL - 1);
        ContainerLivingItemHandler.removeDataByKey(original.getCacheKey());
        ContainerLivingItemHandler.drainRuntimeCacheInvalidations();

        SimpleContainerContext recreated = blockContainer(level, pos);
        assertEquals(original.getCacheKey(), recreated.getCacheKey());
        ContainerLivingItemHandler.putContainerData(recreated, SpongeContainerFluidHandler.EDGE, true);
        assertLifecycleSizes(0, 1, 1, 0);
        assertEquals(0, ContainerLivingItemHandler.sweepUnseenContainers(cutoff, TTL),
            "新条目必须使用新盖章，不能被旧截止时刻收掉");
        assertEquals(Boolean.TRUE,
            ContainerLivingItemHandler.peekContainerDataByPos(level, pos, SpongeContainerFluidHandler.EDGE));
        ContainerLivingItemHandler.removeDataByPos(level, pos);
        assertEquals(1, ContainerLivingItemHandler.drainRuntimeCacheInvalidations().size(), "新位置索引必须命中");
        assertLifecycleSizes(0, 0, 0, 0);
    }

    @Test
    @DisplayName("连续创建和显式删除不同键：盖章、位置、追踪与待作废不累计空壳")
    void repeatedCreateRemove_doesNotLeakLifecycleTables() {
        Level level = overworld();
        for (int cycle = 0; cycle < 32; cycle++) {
            SimpleContainerContext ctx = blockContainer(level, new BlockPos(40 + cycle, 64, 40));
            ContainerLivingItemHandler.trackLivingSlots(ctx.getCacheKey(), Map.of(0, Items.FURNACE), ctx);
            ContainerLivingItemHandler.putContainerData(ctx, SpongeContainerFluidHandler.EDGE, true);
            ContainerLivingItemHandler.removeDataByPos(level, ctx.getAssociatedBlockPositions().get(0));
            assertEquals(1, ContainerLivingItemHandler.drainRuntimeCacheInvalidations().size());
        }
        assertLifecycleSizes(0, 0, 0, 0);
    }

    @Test
    @DisplayName("entry 盖章被注入为过期后，真实 processContext tick 续期保活")
    void processContext_renewsNewlyStaleStamp() {
        Level level = overworld();
        BlockPos pos = new BlockPos(36, 64, 36);
        SimpleContainerContext ctx = blockContainer(level, pos);
        ContainerLivingItemHandler.putContainerData(ctx, SpongeContainerFluidHandler.EDGE, true);
        long cutoff = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(ctx.getCacheKey(), cutoff - TTL - 1);
        int[] completed = {0};
        ContainerTickHooks.replaceForTest(List.of(new ContainerTickHook() {
            @Override
            public void onWriteback(TickableContainerContext context, TickContext tick) {
                completed[0]++;
            }
        }));

        try (MockedStatic<LivingItemManager> ignored = isolateFunctions()) {
            ContainerLivingItemHandler.processContext(ctx, level);
        }
        assertEquals(1, completed[0], "空 tick 必须实际走到写回");
        assertEquals(0, ContainerLivingItemHandler.sweepUnseenContainers(cutoff, TTL));
        assertEquals(Boolean.TRUE,
            ContainerLivingItemHandler.peekContainerDataByPos(level, pos, SpongeContainerFluidHandler.EDGE));
        assertTrue(ContainerLivingItemHandler.drainRuntimeCacheInvalidations().isEmpty());
        assertLifecycleSizes(0, 1, 1, 0);
        String key = ctx.getCacheKey();
        ContainerLivingItemHandler.removeContainerData(ctx, SpongeContainerFluidHandler.EDGE);
        assertLifecycleSizes(0, 1, 1, 0);
        long now = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(key, now - TTL);
        assertEquals(1, ContainerLivingItemHandler.sweepUnseenContainers(now, TTL));
        assertEquals(1, ContainerLivingItemHandler.drainRuntimeCacheInvalidations().size());
        assertLifecycleSizes(0, 0, 0, 0);
    }
}
