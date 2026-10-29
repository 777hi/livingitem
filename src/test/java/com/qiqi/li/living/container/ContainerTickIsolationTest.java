package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.world.level.Level;

class ContainerTickIsolationTest {

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
}
