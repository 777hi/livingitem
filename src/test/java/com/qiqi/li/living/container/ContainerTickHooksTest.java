package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ContainerTickHooks} 的接缝测试。
 *
 * <p>它是跨领域写回顺序的唯一注册表：顺序由注册顺序决定（生产：流体 → 应力 → 红石 tick 开始
 * → 相位），且同一实现类只能注册一次。顺序错乱会让同 tick 的写回互相踩。</p>
 */
class ContainerTickHooksTest {

    private List<ContainerTickHook> saved;

    @BeforeEach
    void isolateStaticRegistry() {
        saved = ContainerTickHooks.snapshotForTest();
        ContainerTickHooks.replaceForTest(List.of());
    }

    @AfterEach
    void restoreStaticRegistry() {
        ContainerTickHooks.replaceForTest(saved);
    }

    private static class RecordingHook implements ContainerTickHook {
        final String name;
        final List<String> log;
        ContainerContext seenCtx;
        TickContext seenTick;

        RecordingHook(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onTickStart(ContainerContext ctx, TickContext tick) {
            log.add(name + ":start");
            seenCtx = ctx;
            seenTick = tick;
        }

        @Override
        public void onWriteback(TickableContainerContext ctx, TickContext tick) {
            log.add(name + ":write");
            seenCtx = ctx;
            seenTick = tick;
        }
    }

    @Test
    @DisplayName("同实现类只注册一次(按类去重)")
    void register_dedupesByClass() {
        List<String> log = new ArrayList<>();
        ContainerTickHooks.register(new RecordingHook("A", log));
        ContainerTickHooks.register(new RecordingHook("B", log));

        ContainerTickHooks.fireTickStart(mock(ContainerContext.class), mock(TickContext.class));

        assertEquals(List.of("A:start"), log, "第二个同类实例不得叠加");
    }

    @Test
    @DisplayName("tick 开始:按注册顺序调用")
    void fireTickStart_inRegistrationOrder() {
        List<String> log = new ArrayList<>();
        // 去重按实现类：顺序测试必须用不同的类（各自匿名子类）
        ContainerTickHooks.register(new RecordingHook("A", log) {});
        ContainerTickHooks.register(new RecordingHook("B", log) {});
        ContainerTickHooks.register(new RecordingHook("C", log) {});

        ContainerTickHooks.fireTickStart(mock(ContainerContext.class), mock(TickContext.class));

        assertEquals(List.of("A:start", "B:start", "C:start"), log);
    }

    @Test
    @DisplayName("写回:按注册顺序调用,且 ctx/tick 原样透传")
    void fireWriteback_inOrderAndPassesSameContext() {
        List<String> log = new ArrayList<>();
        RecordingHook a = new RecordingHook("A", log) {};
        RecordingHook b = new RecordingHook("B", log) {};
        ContainerTickHooks.register(a);
        ContainerTickHooks.register(b);
        TickableContainerContext ctx = mock(TickableContainerContext.class);
        TickContext tick = mock(TickContext.class);

        ContainerTickHooks.fireWriteback(ctx, tick);

        assertEquals(List.of("A:write", "B:write"), log);
        assertSame(ctx, a.seenCtx);
        assertSame(tick, a.seenTick);
        assertSame(ctx, b.seenCtx);
        assertSame(tick, b.seenTick);
    }

    @Test
    @DisplayName("只实现默认方法的钩子:两个阶段都安全空转")
    void defaultHook_isNoop() {
        ContainerTickHooks.register(new ContainerTickHook() {});

        assertDoesNotThrow(() -> ContainerTickHooks.fireTickStart(
            mock(ContainerContext.class), mock(TickContext.class)));
        assertDoesNotThrow(() -> ContainerTickHooks.fireWriteback(
            mock(TickableContainerContext.class), mock(TickContext.class)));
    }
}
