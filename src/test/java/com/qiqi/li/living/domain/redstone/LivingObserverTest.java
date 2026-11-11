package com.qiqi.li.living.domain.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * {@link LivingObserverFunction} 活观测者测试。
 *
 * <p>守卫内容对应设计稿 §5（docs/buffer/活观测者-design.md）：检测口径（内容签名 diff）、
 * 受理三重门（盲区合并）、2gt 延迟 + 2gt 脉冲时序、朝向单边输出 + cap、首次观测脉冲 /
 * 换容器触发、只读、驱动守卫与面输出。</p>
 *
 * <p>驱动方式走生产路径：每拍先 {@code syncContentRevision}（对应 processContext 阶段 1，
 * 观测者的 diff 基准在这里刷新），再 {@code calculate}（阶段 4）。</p>
 */
class LivingObserverTest {

    private static final int WIDTH = 9;
    private static final int SIZE = 27;

    /** 构造一个活物品（已打上 IS_LIVING 标记） */
    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 构造一枚朝右的活观测者 */
    private static ItemStack observer(int count) {
        ItemStack stack = living(Items.OBSERVER, count);
        LivingObserverData.set(stack, LivingObserverData.DEFAULT.withDirection(Pos2D.RIGHT));
        return stack;
    }

    /** 带唯一 containerKey 的测试容器（观测者 diff 依赖账本条目，必须可键控） */
    private static FakeContainerContext keyedContext() {
        return new FakeContainerContext(SIZE, WIDTH, "observer_" + UUID.randomUUID());
    }

    /** 生产路径驱动一拍：syncContentRevision（阶段 1）→ calculate（阶段 4） */
    private static void tickOnce(ContainerRedstoneData data, FakeContainerContext ctx,
            Map<String, Set<Integer>> functionSlots) {
        ContainerLivingItemHandler.syncContentRevision(ctx);
        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(functionSlots);
        data.resetProcessedFlag();
        data.calculate(ctx, tick);
    }

    private static LivingObserverData stateOf(FakeContainerContext ctx, int slot) {
        return LivingObserverData.of(ctx.getItem(slot));
    }

    // ════════════════════════════════════════
    // 时序：受理 → 2gt 延迟 → 2gt 脉冲 → 回待机（对齐原版 scheduleTick(2) × 2）
    // ════════════════════════════════════════

    @Test
    @DisplayName("首次观测必脉冲：2gt 延迟 + 2gt 宽度，逐拍断言状态与信号边沿")
    void firstObservation_pulseTiming() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();

        // t1 = 受理拍：进入开启延迟，尚无输出
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "受理拍不应输出");
        assertEquals(2, stateOf(ctx, 10).pendingTimer(), "受理后应进入 2gt 开启延迟");
        assertEquals(0, data.getSignal(11), "延迟期间无信号");

        // t2 = 延迟第 2 拍
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "延迟期间不应输出");
        assertEquals(1, stateOf(ctx, 10).pendingTimer(), "延迟倒计时应递减");
        assertEquals(0, data.getSignal(11), "延迟期间无信号");

        // t3 = 脉冲第 1 拍（受理 + 2gt 延迟后开始输出）
        tickOnce(data, ctx, slots);
        assertTrue(stateOf(ctx, 10).powered(), "2gt 延迟结束后应进入脉冲");
        assertEquals(15, data.getSignal(11), "脉冲期下游粉应读到满信号（1 堆叠 cap=15）");

        // t4 = 脉冲第 2 拍
        tickOnce(data, ctx, slots);
        assertTrue(stateOf(ctx, 10).powered(), "脉冲宽度应为 2gt（第 2 拍仍在输出）");
        assertEquals(15, data.getSignal(11), "脉冲第 2 拍下游粉仍有信号");

        // t5 = 归零拍：回待机，信号消失
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "2gt 宽度结束后应回待机");
        assertEquals(0, stateOf(ctx, 10).pendingTimer(), "待机态计时器应为 0");
        assertEquals(0, data.getSignal(11), "脉冲结束后信号归零");

        // t6 = 内容未变 ⇒ 不再受理
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "内容未变不应再次触发");
        assertEquals(0, data.getSignal(11), "内容未变无新脉冲");
    }

    @Test
    @DisplayName("内容变化（新增物品）再次触发完整脉冲")
    void contentChange_retriggers() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        for (int t = 0; t < 5; t++) {
            tickOnce(data, ctx, slots);
        }
        assertFalse(stateOf(ctx, 10).powered(), "前置：首次脉冲应已结束");

        // 原版途径放一组石头（远槽，不经 setItem 拦截路径）
        ctx.rawSet(20, living(Items.STONE, 1));
        tickOnce(data, ctx, slots);
        assertEquals(2, stateOf(ctx, 10).pendingTimer(), "内容变化应再次受理");

        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertTrue(stateOf(ctx, 10).powered(), "第二次脉冲应开始输出");
        assertEquals(15, data.getSignal(11), "第二次脉冲下游粉应有信号");

        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "第二次脉冲结束后回待机");
    }

    // ════════════════════════════════════════
    // 检测口径：组件级变化不触发（规则 2）
    // ════════════════════════════════════════

    @Test
    @DisplayName("组件级变化（观测者自身朝向重配置）不触发 —— 签名只看 id+数量+排列")
    void componentChange_doesNotTrigger() {
        var ctx = keyedContext();
        ItemStack obs = observer(1);
        ctx.set(10, obs);
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        for (int t = 0; t < 5; t++) {
            tickOnce(data, ctx, slots);
        }
        assertFalse(stateOf(ctx, 10).powered(), "前置：首次脉冲应已结束");

        // 组件级变化：观测者自身朝向改向下（count/id 不变 ⇒ 签名不变）
        LivingObserverData.set(obs, LivingObserverData.of(obs).withDirection(Pos2D.DOWN));
        for (int t = 0; t < 6; t++) {
            tickOnce(data, ctx, slots);
            assertFalse(stateOf(ctx, 10).powered(),
                "组件变化不应触发脉冲（tick " + t + "）");
            assertEquals(0, stateOf(ctx, 10).pendingTimer(),
                "组件变化不应进入延迟（tick " + t + "）");
        }
    }

    // ════════════════════════════════════════
    // 受理三重门：盲区变化合并为恢复后一次脉冲（规则 3）
    // ════════════════════════════════════════

    @Test
    @DisplayName("盲区合并：延迟/脉冲期间的多次内容变化合并为恢复后恰好一次脉冲")
    void blindWindow_changesMergeIntoOneRecoveryPulse() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();

        // t1 受理；盲区内连改三次内容（t1 后 / t2 后 / t3 后）
        tickOnce(data, ctx, slots);
        ctx.rawSet(20, living(Items.STONE, 1));
        tickOnce(data, ctx, slots);
        ctx.rawSet(21, living(Items.STONE, 1));
        tickOnce(data, ctx, slots);
        ctx.rawSet(22, living(Items.STONE, 1));

        // t4 = 首脉冲第 2 拍：盲区内的变化不得插队
        tickOnce(data, ctx, slots);
        assertTrue(stateOf(ctx, 10).powered(), "首脉冲第 2 拍应正常输出");

        // t5 = 恢复待机拍：脉冲结束，盲区内的全部变化合并为一次新受理
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "恢复拍本身不应在输出");
        assertEquals(2, stateOf(ctx, 10).pendingTimer(), "盲区变化应合并为恢复后一次受理");

        // 恢复脉冲走完 2gt 延迟 + 2gt 宽度，然后不再触发
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertTrue(stateOf(ctx, 10).powered(), "恢复脉冲应输出");
        tickOnce(data, ctx, slots);
        assertTrue(stateOf(ctx, 10).powered(), "恢复脉冲第 2 拍仍输出");
        tickOnce(data, ctx, slots);
        assertFalse(stateOf(ctx, 10).powered(), "恢复脉冲结束");

        // 内容此后未变 ⇒ 不再受理
        tickOnce(data, ctx, slots);
        assertEquals(0, stateOf(ctx, 10).pendingTimer(), "不应再有受理");
        assertFalse(stateOf(ctx, 10).powered(), "不应再有输出");
    }

    // ════════════════════════════════════════
    // 槽间移动算变化（规则 1：签名含槽位排列）＋ 换容器必触发（规则 7）
    // ════════════════════════════════════════

    @Test
    @DisplayName("同容器槽间移动（原版途径）算内容变化，触发受理")
    void slotMove_withinContainer_triggers() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));
        ctx.set(20, living(Items.STONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        for (int t = 0; t < 5; t++) {
            tickOnce(data, ctx, slots);
        }
        assertFalse(stateOf(ctx, 10).powered(), "前置：应已回待机");

        // 原版途径把石头从槽 20 挪到槽 21（排列变化 ⇒ 签名变化）
        ctx.rawSet(20, ItemStack.EMPTY);
        ctx.rawSet(21, living(Items.STONE, 1));
        tickOnce(data, ctx, slots);

        assertEquals(2, stateOf(ctx, 10).pendingTimer(), "槽间移动应被观测为内容变化");
    }

    @Test
    @DisplayName("换容器必触发：lastSeenSig 与新容器内容不符 ⇒ 首拍受理")
    void movingToNewContainer_triggers() {
        var ctxA = keyedContext();
        ctxA.set(10, observer(1));
        ctxA.set(11, living(Items.REDSTONE, 1));
        var slotsA = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var dataA = new ContainerRedstoneData();
        for (int t = 0; t < 5; t++) {
            tickOnce(dataA, ctxA, slotsA);
        }
        assertFalse(stateOf(ctxA, 10).powered(), "前置：容器 A 内应已回待机");

        // 把观测者连组件一起搬到内容不同的容器 B
        var ctxB = keyedContext();
        ctxB.set(12, living(Items.STONE, 3));
        ItemStack moved = observer(1);
        LivingObserverData.set(moved, LivingObserverData.of(ctxA.getItem(10)));
        ctxB.set(10, moved);
        ctxB.set(11, living(Items.REDSTONE, 1));

        var slotsB = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var dataB = new ContainerRedstoneData();
        tickOnce(dataB, ctxB, slotsB);

        assertEquals(2, stateOf(ctxB, 10).pendingTimer(),
            "换容器 = 换观测对象，首拍应受理");
    }

    // ════════════════════════════════════════
    // 输出形状：cap 随堆叠 / 仅朝向单边 / 经粉到灯 / 面输出
    // ════════════════════════════════════════

    @Test
    @DisplayName("堆叠放大输出：4 连观测者 cap=16（n² 家族口径）")
    void cap_scalesWithCount() {
        var ctx = keyedContext();
        ctx.set(10, observer(4));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);

        assertTrue(stateOf(ctx, 10).powered(), "应处于脉冲期");
        assertEquals(16, data.getSignal(11), "4 连堆叠的信号上限 = 4² = 16");
    }

    @Test
    @DisplayName("仅朝向单边输出：朝右时下方邻槽不得收到信号")
    void directionEdge_only() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));   // 右邻（朝向方向）
        ctx.set(19, living(Items.REDSTONE, 1));   // 下邻（非朝向方向）

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11, 19));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);

        assertTrue(stateOf(ctx, 10).powered(), "应处于脉冲期");
        assertEquals(15, data.getSignal(11), "朝向方向应输出");
        assertEquals(0, data.getSignal(19), "非朝向方向不得输出（单边，对齐中继器惯例）");
    }

    @Test
    @DisplayName("端到端：观测者 → 红石粉 → 红石灯，脉冲期点亮、结束后熄灭")
    void observerThroughDust_lampLitDuringPulseOnly() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));
        ItemStack lamp = living(Items.REDSTONE_LAMP, 1);
        ctx.set(12, lamp);

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11),
            LivingRedstoneLampFunction.ID, Set.of(12));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertTrue(LivingRedstoneLampData.of(lamp).lit(), "脉冲期灯应点亮");

        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertFalse(LivingRedstoneLampData.of(lamp).lit(), "脉冲结束后灯应熄灭");
    }

    @Test
    @DisplayName("面输出：边界观测器的出边进入 faceOutput（跨容器/世界红石通路）")
    void faceOutput_atBoundary() {
        var ctx = keyedContext();
        ItemStack obs = observer(1);
        LivingObserverData.set(obs, LivingObserverData.DEFAULT.withDirection(Pos2D.UP));
        ctx.set(0, obs);   // 槽 0 = 顶行左列 ⇒ UP 是边界方向

        var slots = Map.<String, Set<Integer>>of(LivingObserverFunction.ID, Set.of(0));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);

        assertTrue(stateOf(ctx, 0).powered(), "应处于脉冲期");
        assertEquals(15, data.getBoundarySignal(ContainerRedstoneData.EDGE_UP),
            "脉冲期顶面应输出 15（边界面输出通路）");
    }

    // ════════════════════════════════════════
    // 驱动守卫与安全不工作
    // ════════════════════════════════════════

    @Test
    @DisplayName("观测者是容器里唯一的红石元件时账本仍必跑（hasRedstoneElements 纳入）")
    void observerOnlyContainer_stillDrives() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));

        var slots = Map.<String, Set<Integer>>of(LivingObserverFunction.ID, Set.of(10));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);

        assertEquals(2, stateOf(ctx, 10).pendingTimer(),
            "无粉尘无灯的纯观测者容器也要检测受理（否则无人驱动）");
        assertTrue(ContainerRedstoneData.hasRedstoneElements(newTickContext(ctx, slots)),
            "hasRedstoneElements 必须纳入观测者");
    }

    private static TickContext newTickContext(FakeContainerContext ctx, Map<String, Set<Integer>> slots) {
        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(slots);
        return tick;
    }

    @Test
    @DisplayName("无账本条目的上下文（containerKey=null）安全不工作：不受理、不脉冲")
    void keylessContext_safeNoOp() {
        // containerKey=null ⇒ entry 恒 null ⇒ 签名哨兵 ⇒ 观测者恒待机
        var ctx = new FakeContainerContext(SIZE, WIDTH, null);
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        for (int t = 0; t < 6; t++) {
            tickOnce(data, ctx, slots);
            assertEquals(0, stateOf(ctx, 10).pendingTimer(), "无账本条目不应受理（tick " + t + "）");
            assertFalse(stateOf(ctx, 10).powered(), "无账本条目不应输出（tick " + t + "）");
            assertEquals(0, data.getSignal(11), "无账本条目不应有信号（tick " + t + "）");
        }
    }

    // ════════════════════════════════════════
    // 只读（规则 8）：观测不改变任何槽位的物品身份与数量
    // ════════════════════════════════════════

    @Test
    @DisplayName("只读：一整个脉冲周期内，所有槽位的物品类型与数量不变（无消费/无产出）")
    void observerNeverModifiesSlotContents() {
        var ctx = keyedContext();
        ctx.set(10, observer(1));
        ctx.set(11, living(Items.REDSTONE, 1));
        ctx.set(12, living(Items.STONE, 3));

        var slots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        record Snapshot(Item item, int count) {}
        Snapshot[] before = new Snapshot[SIZE];
        for (int i = 0; i < SIZE; i++) {
            ItemStack s = ctx.getItem(i);
            before[i] = s.isEmpty() ? new Snapshot(null, 0) : new Snapshot(s.getItem(), s.getCount());
        }

        var data = new ContainerRedstoneData();
        for (int t = 0; t < 6; t++) {
            tickOnce(data, ctx, slots);
        }

        for (int i = 0; i < SIZE; i++) {
            ItemStack s = ctx.getItem(i);
            if (before[i].item() == null) {
                assertTrue(s.isEmpty(), "槽位 " + i + " 原本为空，不得被写入物品");
            } else {
                assertFalse(s.isEmpty(), "槽位 " + i + " 的物品不得消失");
                assertEquals(before[i].item(), s.getItem(), "槽位 " + i + " 的物品类型不得改变");
                assertEquals(before[i].count(), s.getCount(), "槽位 " + i + " 的数量不得改变");
            }
        }
    }
}
