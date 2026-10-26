package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * 容器级数据「第三方交接」通道的守卫（2026-10-21）。
 *
 * <p>守的是「时机与搬运解耦」这条设计本身：</p>
 * <ol>
 *   <li><b>捕获 → 交接</b>能把数据搬到目标物品（精妙蹲下拾取的核心路径）；</li>
 *   <li><b>没有捕获 / 目标为空</b> ⇒ 一个 Mover 都不该跑（否则会往空气里搬）；</li>
 *   <li><b>一个 Mover 抛异常不连累其它</b>（流体炸了，相位照搬）；</li>
 *   <li><b>每次 capture 都先清残留</b>（上一次 RETURN 没执行也不会串味）。</li>
 * </ol>
 *
 * <p>⚠️ MOVERS 是进程级静态表（与 `MenuSlotOwners` 同范式）⇒ 各用例注册自己的 Mover、
 * 用自己的计数器断言，互不干扰；ThreadLocal 用 {@link ContainerHandoff#discard()} 收尾。</p>
 */
class ContainerHandoffTest {

    @AfterEach
    void tearDown() {
        ContainerHandoff.discard();
    }

    private static ItemStack stack() {
        return new ItemStack(Items.STONE);
    }

    /**
     * 只关心「搬出」方向的测试替身。
     *
     * <p>⚠️ 生产实现**不能**这么写：{@code Mover} 的 {@code moveIn} 没有 default 就是被
     * 方法引用静默漏实现的坑教育出来的（见 {@code Mover} 的 javadoc）。测试替身里空实现是刻意的。</p>
     */
    private static ContainerHandoff.Mover outOnly(java.util.function.Consumer<ItemStack> action) {
        return new ContainerHandoff.Mover() {
            @Override
            public void moveOut(IAttachmentHolder host, ItemStack target) {
                action.accept(target);
            }

            @Override
            public void moveIn(IAttachmentHolder host, ItemStack source) {}
        };
    }

    @AfterEach
    void clearMovers() {
        ContainerHandoff.clearMoversForTest();
    }

    @Test
    @DisplayName("捕获宿主后交接：数据被搬到目标物品")
    void captureThenHandoff_movesDataToTarget() {
        IAttachmentHolder host = Mockito.mock(IAttachmentHolder.class);
        AtomicInteger calls = new AtomicInteger();
        ContainerHandoff.registerMover(outOnly(target -> {
            calls.incrementAndGet();
            target.set(DataComponents.CUSTOM_NAME, Component.literal("moved"));
        }));

        ContainerHandoff.capture(host);
        ItemStack target = stack();

        assertTrue(ContainerHandoff.handoff(target), "有捕获且目标非空 ⇒ 应执行交接");
        assertEquals(1, calls.get(), "Mover 应被调用一次");
        assertEquals("moved", target.get(DataComponents.CUSTOM_NAME).getString(), "数据应落到目标物品上");
        assertFalse(ContainerHandoff.hasPending(), "交接后捕获应被清空");
    }

    @Test
    @DisplayName("没有捕获时交接是 no-op")
    void handoffWithoutCapture_isNoOp() {
        AtomicInteger calls = new AtomicInteger();
        ContainerHandoff.registerMover(outOnly(t -> calls.incrementAndGet()));

        assertFalse(ContainerHandoff.handoff(stack()), "没有捕获 ⇒ 返回 false");
        assertEquals(0, calls.get(), "没有捕获 ⇒ 一个 Mover 都不该跑");
    }

    @Test
    @DisplayName("目标为空栈：不搬，且捕获被丢弃")
    void handoffWithEmptyTarget_doesNotCallMover() {
        IAttachmentHolder host = Mockito.mock(IAttachmentHolder.class);
        AtomicInteger calls = new AtomicInteger();
        ContainerHandoff.registerMover(outOnly(t -> calls.incrementAndGet()));

        ContainerHandoff.capture(host);
        assertFalse(ContainerHandoff.handoff(ItemStack.EMPTY), "空栈 ⇒ 返回 false");
        assertEquals(0, calls.get(), "空栈 ⇒ 不该往空气里搬");
        assertFalse(ContainerHandoff.hasPending(), "捕获应被丢弃（不留残留）");
    }

    @Test
    @DisplayName("capture(null) 清掉上一次的残留")
    void captureNull_clearsPrevious() {
        ContainerHandoff.capture(Mockito.mock(IAttachmentHolder.class));
        assertTrue(ContainerHandoff.hasPending());

        ContainerHandoff.capture(null);
        assertFalse(ContainerHandoff.hasPending(), "null 等价于「这次没有可交接的宿主」");
        assertFalse(ContainerHandoff.handoff(stack()));
    }

    @Test
    @DisplayName("一个 Mover 抛异常：其它 Mover 照跑，交接仍算完成")
    void moverThrowing_doesNotBlockOthers() {
        AtomicInteger healthy = new AtomicInteger();
        ContainerHandoff.registerMover(outOnly(t -> {
            throw new IllegalStateException("模拟领域内部炸了");
        }));
        ContainerHandoff.registerMover(outOnly(t -> healthy.incrementAndGet()));

        ContainerHandoff.capture(Mockito.mock(IAttachmentHolder.class));
        assertTrue(ContainerHandoff.handoff(stack()), "有捕获 ⇒ 仍应返回 true");
        assertEquals(1, healthy.get(), "坏 Mover 不该连累好 Mover");
    }

    @Test
    @DisplayName("discard 丢弃捕获")
    void discard_clearsPending() {
        ContainerHandoff.capture(Mockito.mock(IAttachmentHolder.class));
        ContainerHandoff.discard();
        assertFalse(ContainerHandoff.hasPending());
    }

    // ─────────────────── 放置方向：物品 → 宿主（2026-10-21 真机日志驱动）───────────────────

    @Test
    @DisplayName("暂存 → 恢复：数据从物品回到宿主")
    void stageThenRestore_callsMoveIn() {
        IAttachmentHolder host = Mockito.mock(IAttachmentHolder.class);
        AtomicInteger calls = new AtomicInteger();
        ContainerHandoff.registerMover(new ContainerHandoff.Mover() {
            @Override
            public void moveOut(IAttachmentHolder h, ItemStack target) {}

            @Override
            public void moveIn(IAttachmentHolder h, ItemStack source) {
                calls.incrementAndGet();
            }
        });

        ContainerHandoff.stageRestore(new ItemStack(Items.SHULKER_BOX));

        assertTrue(ContainerHandoff.restoreTo(host), "有暂存且宿主非空 ⇒ 应执行恢复");
        assertEquals(1, calls.get(), "moveIn 应被调用一次");
        assertFalse(ContainerHandoff.hasPendingRestore(), "恢复后暂存应被清空");
    }

    @Test
    @DisplayName("暂存的是副本：源栈被 shrink 后仍能恢复（精妙 tryPlace 会就地消耗手持栈）")
    void stageRestore_copiesStack_soShrinkDoesNotLoseData() {
        ItemStack source = new ItemStack(Items.SHULKER_BOX);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("still-here"));

        ContainerHandoff.stageRestore(source);
        source.shrink(1);   // 模拟精妙 tryPlace 末尾的 getItemInHand().shrink(1)
        assertTrue(source.isEmpty(), "前置：源栈已被消耗");

        AtomicInteger calls = new AtomicInteger();
        ContainerHandoff.registerMover(new ContainerHandoff.Mover() {
            @Override
            public void moveOut(IAttachmentHolder h, ItemStack target) {}

            @Override
            public void moveIn(IAttachmentHolder h, ItemStack staged) {
                calls.incrementAndGet();
                assertEquals("still-here", staged.get(DataComponents.CUSTOM_NAME).getString(),
                    "暂存必须是副本 —— 否则 RETURN 时读到的是被 shrink 成空的同一个对象");
            }
        });

        assertTrue(ContainerHandoff.restoreTo(Mockito.mock(IAttachmentHolder.class)));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("没有暂存 / 宿主为空 ⇒ 恢复是 no-op")
    void restoreTo_withoutStaging_isNoOp() {
        AtomicInteger calls = new AtomicInteger();
        ContainerHandoff.registerMover(new ContainerHandoff.Mover() {
            @Override
            public void moveOut(IAttachmentHolder h, ItemStack target) {}

            @Override
            public void moveIn(IAttachmentHolder h, ItemStack source) {
                calls.incrementAndGet();
            }
        });

        assertFalse(ContainerHandoff.restoreTo(Mockito.mock(IAttachmentHolder.class)), "没暂存 ⇒ false");
        ContainerHandoff.stageRestore(new ItemStack(Items.SHULKER_BOX));
        assertFalse(ContainerHandoff.restoreTo(null), "宿主为空 ⇒ false");
        assertEquals(0, calls.get(), "两种退化输入都不该调用 moveIn");
    }

    @Test
    @DisplayName("stageRestore(EMPTY) 清掉上一次的暂存")
    void stageRestoreEmpty_clearsPrevious() {
        ContainerHandoff.stageRestore(new ItemStack(Items.SHULKER_BOX));
        assertTrue(ContainerHandoff.hasPendingRestore());

        ContainerHandoff.stageRestore(ItemStack.EMPTY);
        assertFalse(ContainerHandoff.hasPendingRestore(), "空栈等价于「这次没有要恢复的」");
    }

    @Test
    @DisplayName("守卫：Mover 不得有 default 方法（否则方法引用会静默漏掉另一个方向）")
    void mover_hasNoDefaultMethods() {
        // 2026-10-21 真机三连失败的真凶：moveIn 曾是 default 空实现，注册处写
        // registerMover(ContainerDataMigration::moveOut) ⇒ 方法引用只覆盖 moveOut，
        // moveIn 静默失效 ⇒ 拾取成功、放置全空，而所有日志都显示"成功"。
        // 编译期只能挡住「没实现抽象方法」，挡不住「有人把 default 加回来」⇒ 这条反射守卫钉住约定。
        for (var method : ContainerHandoff.Mover.class.getDeclaredMethods()) {
            assertFalse(method.isDefault(),
                "Mover." + method.getName() + " 不得是 default 方法 —— 注册点用方法引用时会静默漏实现");
        }
    }
}
