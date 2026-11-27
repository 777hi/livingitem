package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.brewing.BrewingStandSegment;
import com.qiqi.li.living.domain.brewing.BrewingStandSegment.BrewingRuntime;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

class ContainerTickIsolationTest {

    private List<ContainerTickHook> savedHooks;
    private MockedStatic<LivingItemManager> functions;
    private Level level;
    private int nextPosition;

    @BeforeEach
    void isolatePipeline() {
        savedHooks = ContainerTickHooks.snapshotForTest();
        ContainerTickHooks.replaceForTest(List.of());
        ContainerLivingItemHandler.clearAllCaches();
        LivingItemFunction function = new NoOpLivingFunction();
        functions = mockStatic(LivingItemManager.class, CALLS_REAL_METHODS);
        functions.when(() -> LivingItemManager.getApplicableFunctions(any(ItemStack.class)))
            .thenReturn(List.of());
        functions.when(LivingItemManager::getSelfSustainingFunctions).thenReturn(List.of(function));
        level = Mockito.mock(Level.class);
        Mockito.when(level.dimension()).thenReturn(Level.OVERWORLD);
        MinecraftServer server = Mockito.mock(MinecraftServer.class);
        PlayerList players = Mockito.mock(PlayerList.class);
        Mockito.when(level.getServer()).thenReturn(server);
        Mockito.when(server.getPlayerList()).thenReturn(players);
        Mockito.when(players.getPlayers()).thenReturn(List.of());
    }

    @AfterEach
    void restorePipeline() {
        functions.close();
        ContainerTickHooks.replaceForTest(savedHooks);
        ContainerLivingItemHandler.clearAllCaches();
    }

    @Test
    @DisplayName("RuntimeException 被隔离：processIsolated 向外不抛，重复调用也不抛")
    void runtimeException_isContained() {
        TickableContainerContext ctx = Mockito.mock(TickableContainerContext.class);
        Mockito.when(ctx.getContainerKey()).thenReturn("test_container");
        Mockito.when(ctx.getSize()).thenThrow(new RuntimeException("boom"));
        Level level = Mockito.mock(Level.class);

        assertDoesNotThrow(() -> ContainerLivingItemHandler.processIsolated(ctx, level));
        assertDoesNotThrow(() -> ContainerLivingItemHandler.processIsolated(ctx, level));
    }

    @Test
    @DisplayName("取 key 本身抛异常时仍能隔离（退化为类型名）")
    void keyFailure_doesNotEscape() {
        TickableContainerContext ctx = Mockito.mock(TickableContainerContext.class);
        Mockito.when(ctx.getContainerKey()).thenThrow(new IllegalStateException("bad identity"));
        Mockito.when(ctx.getSize()).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() ->
            ContainerLivingItemHandler.processIsolated(ctx, Mockito.mock(Level.class)));
    }

    @Test
    @DisplayName("Error 不被吞：OOM / 栈溢出照常抛出")
    void error_propagates() {
        TickableContainerContext ctx = Mockito.mock(TickableContainerContext.class);
        Mockito.when(ctx.getSize()).thenThrow(new AssertionError("fatal"));

        assertThrows(AssertionError.class, () ->
            ContainerLivingItemHandler.processIsolated(ctx, Mockito.mock(Level.class)));
    }

    @Test
    @DisplayName("执行中整键作废：旧上下文不得复活缓存或覆盖同键新容器")
    void invalidatedTick_cannotReadOrOverwriteReplacementRuntime() {
        BlockPos pos = new BlockPos(40, 64, 1);
        var old = new SimpleContainerContext(new ItemStackHandler(27), null, List.of(pos), List.of(), level);
        var replacement = new SimpleContainerContext(new ItemStackHandler(27), null, List.of(pos), List.of(), level);
        String key = old.getCacheKey();
        assertEquals(key, replacement.getCacheKey());
        var oldData = RuntimeSegments.EMPTY.with(BrewingStandSegment.INSTANCE,
            new BrewingRuntime(20, java.util.Optional.empty()));
        var newData = RuntimeSegments.EMPTY.with(BrewingStandSegment.INSTANCE,
            new BrewingRuntime(400, java.util.Optional.empty()));
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext context, TickContext tick) {
                if (context == replacement) {
                    assertSame(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(replacement, 0));
                    ContainerRuntimeCache.update(replacement, 0, newData);
                    return;
                }
                ContainerRuntimeCache.update(old, 0, oldData);
                ContainerLivingItemHandler.removeDataByKey(key);
                ContainerRuntimeCache.update(old, 0, oldData);
                assertTrue(ContainerRuntimeCache.getSnapshot(key).isEmpty());

                ContainerLivingItemHandler.processContext(replacement, level);
                assertSame(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(old, 0));
                ContainerRuntimeCache.update(old, 0, oldData);
            }
        });
        try {
            ContainerLivingItemHandler.processContext(old, level);
            assertSame(newData, ContainerRuntimeCache.get(key, 0));
        } finally {
            ContainerRuntimeCache.removeContainer(key);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("槽位映射中整键作废并重建：旧 get 返回空，旧 update 不覆盖同键新值")
    void invalidatedDuringSlotMapping_cannotAccessReplacementRuntime(boolean update) {
        BlockPos pos = new BlockPos(43, 64, 1);
        class MappingContext extends SimpleContainerContext {
            private Runnable duringMapping;

            MappingContext(Level level) {
                super(new ItemStackHandler(2), null, List.of(pos), List.of(), level);
            }

            void activate(Runnable action) {
                duringMapping = action;
            }

            @Override
            public int toPhysicalSlot(int logicalSlot) {
                Runnable action = duringMapping;
                duringMapping = null;
                if (action != null) action.run();
                return logicalSlot + 1;
            }
        }
        var old = new MappingContext(level);
        var replacement = new MappingContext(level);
        String key = old.getCacheKey();
        assertEquals(key, replacement.getCacheKey());
        var oldData = RuntimeSegments.EMPTY.with(BrewingStandSegment.INSTANCE,
            new BrewingRuntime(20, java.util.Optional.empty()));
        var newData = RuntimeSegments.EMPTY.with(BrewingStandSegment.INSTANCE,
            new BrewingRuntime(400, java.util.Optional.empty()));
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext context, TickContext tick) {
                if (context == replacement) {
                    assertSame(tick, replacement.getTickContext());
                    ContainerRuntimeCache.update(replacement, 0, newData);
                    return;
                }
                assertSame(tick, old.getTickContext());
                // 仅在旧 tick 绑定后启动一次，避免在扫槽时触发作废。
                old.activate(() -> {
                    ContainerLivingItemHandler.removeDataByKey(key);
                    ContainerLivingItemHandler.processContext(replacement, level);
                });
                if (update) {
                    ContainerRuntimeCache.update(old, 0, oldData);
                } else {
                    assertSame(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(old, 0),
                        "旧 get 不得读到映射期间写入的同键新值");
                }
            }
        });

        try {
            ContainerLivingItemHandler.processContext(old, level);
            assertSame(newData, ContainerRuntimeCache.get(key, 1), "物理槽应保留同键新容器写入的值");
        } finally {
            ContainerRuntimeCache.removeContainer(key);
        }
    }

    @Test
    @DisplayName("首次扫槽中整键作废并重建：旧扫描不能改写新容器或触发旧钩子")
    void invalidatedScan_cannotCommitToSameKeyReplacement() {
        BlockPos pos = new BlockPos(41, 64, 1);
        var handler = spy(new ItemStackHandler(1));
        var old = new SimpleContainerContext(handler, null, List.of(pos), List.of(), level);
        var replacementHandler = new ItemStackHandler(1);
        replacementHandler.setStackInSlot(0, new ItemStack(Items.STONE));
        var replacement = new SimpleContainerContext(replacementHandler, null, List.of(pos), List.of(), level);
        String key = old.getCacheKey();
        assertEquals(key, replacement.getCacheKey());
        var oldData = RuntimeSegments.EMPTY.with(BrewingStandSegment.INSTANCE,
            new BrewingRuntime(20, java.util.Optional.empty()));
        var newData = RuntimeSegments.EMPTY.with(BrewingStandSegment.INSTANCE,
            new BrewingRuntime(400, java.util.Optional.empty()));
        var starts = new ArrayList<ContainerContext>();
        var replacementRevision = new AtomicLong();
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext context, TickContext tick) {
                starts.add(context);
                ContainerRuntimeCache.update(context, 0, context == replacement ? newData : oldData);
            }
        });
        doAnswer(call -> {
            ContainerLivingItemHandler.removeDataByKey(key);
            ContainerLivingItemHandler.processContext(replacement, level);
            replacementRevision.set(ContainerLivingItemHandler.getContainerRevision(replacement));
            return ItemStack.EMPTY;
        }).doCallRealMethod().when(handler).getStackInSlot(0);

        try {
            ContainerLivingItemHandler.processContext(old, level);

            assertAll(
                () -> assertEquals(List.of(replacement), starts, "只有替代容器进入开始钩子"),
                () -> assertTrue(replacementRevision.get() > 0, "替代容器正常完成首次扫描"),
                () -> assertEquals(replacementRevision.get(), ContainerLivingItemHandler.getContainerRevision(replacement),
                    "旧扫描签名不得推进替代容器的 revision"),
                () -> assertSame(newData, ContainerRuntimeCache.get(key, 0), "旧容器不得写入替代容器的缓存"));
        } finally {
            ContainerRuntimeCache.removeContainer(key);
        }
    }

    @Test
    @DisplayName("扫槽中整键作废且不重建：旧扫描不复活聚合条目，下次处理正常")
    void invalidatedScan_doesNotRecreateRemovedEntry() {
        var handler = spy(new ItemStackHandler(1));
        var ctx = new SimpleContainerContext(handler, null, List.of(new BlockPos(42, 64, 1)), List.of(), level);
        String emptyLifecycle = ContainerLivingItemHandler.describeLifecycleStats();
        ContainerLivingItemHandler.bumpContainerRevision(ctx);
        var hook = Mockito.mock(ContainerTickHook.class);
        ContainerTickHooks.register(hook);
        doAnswer(call -> {
            ContainerLivingItemHandler.removeDataByKey(ctx.getCacheKey());
            return ItemStack.EMPTY;
        }).doCallRealMethod().when(handler).getStackInSlot(0);

        ContainerLivingItemHandler.processContext(ctx, level);

        assertAll(
            () -> assertEquals(0L, ContainerLivingItemHandler.getContainerRevision(ctx)),
            () -> assertEquals(emptyLifecycle, ContainerLivingItemHandler.describeLifecycleStats()),
            () -> Mockito.verifyNoInteractions(hook));
        ContainerLivingItemHandler.processContext(ctx, level);
        verify(hook).onTickStart(same(ctx), any(TickContext.class));
        assertTrue(ContainerLivingItemHandler.getContainerRevision(ctx) > 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("开始钩子递归同一对象：保留外层 tick，正常或异常退出后均可再处理")
    void tickStartReentry_preservesOuterTickAndReleasesGuard(boolean failFirstTick) {
        SimpleContainerContext ctx = context();
        var starts = new ArrayList<TickContext>();
        var writebacks = new ArrayList<TickContext>();
        var failure = new IllegalStateException("outer start");
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext context, TickContext tick) {
                starts.add(tick);
                if (starts.size() != 1) return;
                context.syncSlotToClients(0, ItemStack.EMPTY);
                ContainerLivingItemHandler.processContext(ctx, level);
                assertSame(tick, ctx.getTickContext(), "内层调用不得解绑外层 tick");
                assertEquals(1, starts.size(), "内层调用不得执行开始钩子");
                assertTrue(writebacks.isEmpty(), "内层调用不得写回");
                assertTrue(tick.dirtySlots.contains(0), "外层脏槽留给外层刷新");
                if (failFirstTick) throw failure;
            }

            @Override
            public void onWriteback(TickableContainerContext context, TickContext tick) {
                assertSame(tick, ctx.getTickContext());
                writebacks.add(tick);
            }
        });

        if (failFirstTick) {
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                ContainerLivingItemHandler.processContext(ctx, level)));
        } else {
            ContainerLivingItemHandler.processContext(ctx, level);
        }
        assertEquals(1, starts.size());
        TickContext first = starts.getFirst();
        assertEquals(failFirstTick ? List.of() : List.of(first), writebacks);
        assertTrue(first.dirtySlots.isEmpty());
        assertNull(ctx.getTickContext());

        ContainerLivingItemHandler.processContext(ctx, level);

        assertEquals(2, starts.size());
        TickContext next = starts.getLast();
        assertEquals(failFirstTick ? List.of(next) : List.of(first, next), writebacks);
        verify(ctx, times(2)).flushDirtySlots();
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("扫槽递归同一对象：在读取槽位之前拦截内层处理")
    void scanReentry_skipsNestedSlotReadsAndHooks() {
        var handler = spy(new ItemStackHandler(1));
        var ctx = new SimpleContainerContext(handler, level);
        var hook = Mockito.mock(ContainerTickHook.class);
        ContainerTickHooks.register(hook);
        doAnswer(call -> {
            ContainerLivingItemHandler.processContext(ctx, level);
            return ItemStack.EMPTY;
        }).doCallRealMethod().when(handler).getStackInSlot(0);

        ContainerLivingItemHandler.processContext(ctx, level);

        assertAll(
            () -> verify(handler).getStackInSlot(0),
            () -> verify(hook).onTickStart(same(ctx), any(TickContext.class)),
            () -> verify(hook).onWriteback(same(ctx), any(TickContext.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("首个开始或写回钩子作废容器：停止后续钩子，不刷新旧脏槽但仍解绑")
    void invalidatedHook_stopsPipelineAndSkipsDirtyFlush(boolean invalidateOnWriteback) {
        SimpleContainerContext ctx = context();
        var phases = new ArrayList<String>();
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext context, TickContext tick) {
                phases.add("first start");
                context.syncSlotToClients(0, ItemStack.EMPTY);
                if (!invalidateOnWriteback) invalidate(tick);
            }

            @Override
            public void onWriteback(TickableContainerContext context, TickContext tick) {
                phases.add("first writeback");
                if (invalidateOnWriteback) invalidate(tick);
            }

            private void invalidate(TickContext tick) {
                assertSame(tick, ctx.getTickContext());
                assertTrue(tick.dirtySlots.contains(0), "作废前已有待刷新的脏槽");
                ContainerLivingItemHandler.removeDataByKey(ctx.getCacheKey());
            }
        });
        ContainerTickHooks.register(new ContainerTickHook() {
            @Override
            public void onTickStart(ContainerContext context, TickContext tick) {
                phases.add("second start");
            }

            @Override
            public void onWriteback(TickableContainerContext context, TickContext tick) {
                phases.add("second writeback");
            }
        });

        ContainerLivingItemHandler.processContext(ctx, level);

        assertAll(
            () -> assertEquals(invalidateOnWriteback
                ? List.of("first start", "second start", "first writeback")
                : List.of("first start"), phases),
            () -> verify(ctx, Mockito.never()).flushDirtySlots(),
            () -> assertNull(ctx.getTickContext()));
    }

    @Test
    @DisplayName("正常 tick 刷新脏槽并解绑")
    void successfulTick_flushesAndUnbinds() {
        SimpleContainerContext ctx = context();
        FaultHook hook = hook(null, null);

        ContainerLivingItemHandler.processContext(ctx, level);

        assertEquals(1, hook.starts);
        assertEquals(1, hook.writebacks);
        assertTrue(hook.tick.dirtySlots.isEmpty());
        assertNull(ctx.getTickContext());
        verify(ctx).flushDirtySlots();
    }

    @Test
    @DisplayName("开始钩子失败也刷新已登记脏槽并解绑")
    void startFailure_flushesAndUnbinds() {
        SimpleContainerContext ctx = context();
        RuntimeException failure = new IllegalStateException("start");
        FaultHook hook = hook(failure, null);

        assertSame(failure, assertThrows(RuntimeException.class, () ->
            ContainerLivingItemHandler.processContext(ctx, level)));

        assertEquals(1, hook.starts);
        assertEquals(0, hook.writebacks);
        assertTrue(hook.tick.dirtySlots.isEmpty());
        assertNull(ctx.getTickContext());
        verify(ctx).flushDirtySlots();
    }

    @Test
    @DisplayName("写回失败也刷新脏槽并解绑")
    void writebackFailure_flushesAndUnbinds() {
        SimpleContainerContext ctx = context();
        RuntimeException failure = new IllegalStateException("writeback");
        FaultHook hook = hook(null, failure);

        assertSame(failure, assertThrows(RuntimeException.class, () ->
            ContainerLivingItemHandler.processContext(ctx, level)));

        assertEquals(1, hook.writebacks);
        assertTrue(hook.tick.dirtySlots.isEmpty());
        assertNull(ctx.getTickContext());
        verify(ctx).flushDirtySlots();
    }

    @Test
    @DisplayName("脏槽刷新失败仍解绑，异常原样抛出")
    void flushFailure_unbinds() {
        SimpleContainerContext ctx = context();
        RuntimeException failure = new IllegalStateException("flush");
        hook(null, null);
        doThrow(failure).when(ctx).flushDirtySlots();

        assertSame(failure, assertThrows(RuntimeException.class, () ->
            ContainerLivingItemHandler.processContext(ctx, level)));
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("业务与刷新同时失败：保留业务异常，刷新异常作 suppressed")
    void doubleFailure_preservesBothExceptions() {
        SimpleContainerContext ctx = context();
        RuntimeException business = new IllegalStateException("writeback");
        RuntimeException flush = new IllegalStateException("flush");
        hook(null, business);
        doThrow(flush).when(ctx).flushDirtySlots();

        assertSame(business, assertThrows(RuntimeException.class, () ->
            ContainerLivingItemHandler.processContext(ctx, level)));
        assertEquals(List.of(flush), List.of(business.getSuppressed()));
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("开始与刷新同时失败也保留两份异常并解绑")
    void startAndFlushFailure_preservesBothExceptions() {
        SimpleContainerContext ctx = context();
        RuntimeException start = new IllegalStateException("start");
        RuntimeException flush = new IllegalStateException("flush");
        hook(start, null);
        doThrow(flush).when(ctx).flushDirtySlots();

        assertSame(start, assertThrows(RuntimeException.class, () ->
            ContainerLivingItemHandler.processContext(ctx, level)));
        assertEquals(List.of(flush), List.of(start.getSuppressed()));
        assertNull(ctx.getTickContext());
        verify(ctx).flushDirtySlots();
    }

    @Test
    @DisplayName("业务与刷新抛同一异常对象时不自抑制")
    void sameFailure_doesNotSuppressItself() {
        SimpleContainerContext ctx = context();
        RuntimeException failure = new IllegalStateException("shared");
        hook(null, failure);
        doThrow(failure).when(ctx).flushDirtySlots();

        assertSame(failure, assertThrows(RuntimeException.class, () ->
            ContainerLivingItemHandler.processContext(ctx, level)));
        assertEquals(0, failure.getSuppressed().length);
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("开始钩子的 Error 仍传播，但先完成刷新与解绑")
    void startError_unbindsAndPropagates() {
        SimpleContainerContext ctx = context();
        AssertionError failure = new AssertionError("start");
        FaultHook hook = hook(failure, null);

        assertSame(failure, assertThrows(AssertionError.class, () ->
            ContainerLivingItemHandler.processIsolated(ctx, level)));
        assertTrue(hook.tick.dirtySlots.isEmpty());
        assertNull(ctx.getTickContext());
        verify(ctx).flushDirtySlots();
    }

    @Test
    @DisplayName("业务 Error 不被刷新 RuntimeException 覆盖或隔离")
    void businessError_remainsPrimary() {
        SimpleContainerContext ctx = context();
        AssertionError business = new AssertionError("writeback");
        RuntimeException flush = new IllegalStateException("flush");
        hook(null, business);
        doThrow(flush).when(ctx).flushDirtySlots();

        assertSame(business, assertThrows(AssertionError.class, () ->
            ContainerLivingItemHandler.processIsolated(ctx, level)));
        assertEquals(List.of(flush), List.of(business.getSuppressed()));
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("刷新 Error 优先于业务 RuntimeException，不能被隔离层吞掉")
    void flushError_isNotHiddenByBusinessFailure() {
        SimpleContainerContext ctx = context();
        RuntimeException business = new IllegalStateException("writeback");
        AssertionError flush = new AssertionError("flush");
        hook(null, business);
        doThrow(flush).when(ctx).flushDirtySlots();

        assertSame(flush, assertThrows(AssertionError.class, () ->
            ContainerLivingItemHandler.processIsolated(ctx, level)));
        assertEquals(List.of(business), List.of(flush.getSuppressed()));
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("单独刷新 Error 原样传播且解绑")
    void flushError_unbindsAndPropagates() {
        SimpleContainerContext ctx = context();
        AssertionError flush = new AssertionError("flush");
        hook(null, null);
        doThrow(flush).when(ctx).flushDirtySlots();

        assertSame(flush, assertThrows(AssertionError.class, () ->
            ContainerLivingItemHandler.processIsolated(ctx, level)));
        assertNull(ctx.getTickContext());
    }

    @Test
    @DisplayName("失败容器解绑后，下一容器照常处理")
    void isolatedFailure_doesNotAffectNextContainer() {
        SimpleContainerContext failed = context();
        SimpleContainerContext next = context();
        FaultHook hook = hook(null, new IllegalStateException("writeback"));
        doThrow(new IllegalStateException("flush")).when(failed).flushDirtySlots();

        assertDoesNotThrow(() -> ContainerLivingItemHandler.processIsolated(failed, level));
        assertNull(failed.getTickContext());
        hook.writebackFailure = null;
        assertDoesNotThrow(() -> ContainerLivingItemHandler.processIsolated(next, level));

        assertEquals(2, hook.starts);
        assertEquals(2, hook.writebacks);
        assertTrue(hook.tick.dirtySlots.isEmpty());
        assertNull(next.getTickContext());
        verify(next).flushDirtySlots();
    }

    private SimpleContainerContext context() {
        return spy(new SimpleContainerContext(new ItemStackHandler(9), null,
            List.of(new BlockPos(nextPosition++, 64, 0)), List.of(), level));
    }

    private static FaultHook hook(Throwable startFailure, Throwable writebackFailure) {
        FaultHook hook = new FaultHook(startFailure, writebackFailure);
        ContainerTickHooks.register(hook);
        return hook;
    }

    private static final class FaultHook implements ContainerTickHook {
        private final Throwable startFailure;
        private Throwable writebackFailure;
        private int starts;
        private int writebacks;
        private TickContext tick;

        private FaultHook(Throwable startFailure, Throwable writebackFailure) {
            this.startFailure = startFailure;
            this.writebackFailure = writebackFailure;
        }

        @Override
        public void onTickStart(ContainerContext ctx, TickContext tick) {
            starts++;
            this.tick = tick;
            ctx.syncSlotToClients(0, ctx.getItem(0));
            throwFailure(startFailure);
        }

        @Override
        public void onWriteback(TickableContainerContext ctx, TickContext tick) {
            writebacks++;
            throwFailure(writebackFailure);
        }

        private static void throwFailure(Throwable failure) {
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
        }
    }

    private static final class NoOpLivingFunction implements LivingItemFunction {
        @Override
        public boolean canApply(ItemStack stack) {
            return false;
        }

        @Override
        public String getFunctionId() {
            return "test_tick_cleanup";
        }

        @Override
        public boolean shouldTickWithoutOwnItems(ContainerContext ctx) {
            return true;
        }

        @Override
        public void tick(List<SlotEntry> entries, ContainerContext ctx, TickContext tick, Level level) {
        }
    }
}
