package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
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
