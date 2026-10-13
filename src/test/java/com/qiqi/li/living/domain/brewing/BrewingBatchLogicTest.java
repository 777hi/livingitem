package com.qiqi.li.living.domain.brewing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 活酿造台批次状态机的纯逻辑测试。
 *
 * <p>判据来源：原版 {@code BrewingStandBlockEntity.serverTick}（设计稿 §1.6 / §4.5）+ D7 / D8 / D11。
 * 每条断言都对应一条已定案的规则，避免「实现漂移」。</p>
 */
class BrewingBatchLogicTest {

    private static final Item WART = Items.NETHER_WART;
    private static final Item GLOWSTONE = Items.GLOWSTONE_DUST;
    private static final int FULL = BrewingBatchLogic.BREW_TIME;
    private static final int FUEL = BrewingBatchLogic.FUEL_USES;

    private static final Optional<Item> NO_BATCH = Optional.empty();
    private static final Optional<Item> WART_BATCH = Optional.of(WART);

    // ---------------- 启动（D7：启动扣 1 次燃料）----------------

    @Test
    @DisplayName("空闲 + 可酿造 + 有燃料 ⇒ 启动 400 tick 批次，并记录材料")
    void idle_startsBatch() {
        var step = BrewingBatchLogic.advance(0, NO_BATCH, true, WART, FUEL, 1);

        var start = assertInstanceOf(BrewingBatchLogic.Step.Start.class, step);
        assertEquals(FULL, start.brewTime());
        assertEquals(WART, start.ingredient());
    }

    @Test
    @DisplayName("有燃料但不可酿造（材料失效 / 无可配瓶子）⇒ 不启动")
    void notBrewable_doesNotStart() {
        assertInstanceOf(BrewingBatchLogic.Step.Idle.class,
            BrewingBatchLogic.advance(0, NO_BATCH, false, WART, FUEL, 1));
    }

    @Test
    @DisplayName("可酿造但燃料为 0 ⇒ 不启动")
    void noFuel_doesNotStart() {
        assertInstanceOf(BrewingBatchLogic.Step.Idle.class,
            BrewingBatchLogic.advance(0, NO_BATCH, true, WART, 0, 1));
    }

    // ---------------- 推进 / 完成 ----------------

    @Test
    @DisplayName("酿造中且一切正常 ⇒ 倒计时按 step 递减")
    void brewing_advancesByStep() {
        var step = BrewingBatchLogic.advance(FULL, WART_BATCH, true, WART, 0, 1);
        assertEquals(FULL - 1, assertInstanceOf(BrewingBatchLogic.Step.Continue.class, step).brewTime());

        var fast = BrewingBatchLogic.advance(FULL, WART_BATCH, true, WART, 0, 8);
        assertEquals(FULL - 8, assertInstanceOf(BrewingBatchLogic.Step.Continue.class, fast).brewTime());
    }

    @Test
    @DisplayName("倒计时归零且仍可酿造 ⇒ 完成（D7：完成扣 1 份材料）")
    void countdownReachesZero_completes() {
        assertInstanceOf(BrewingBatchLogic.Step.Complete.class,
            BrewingBatchLogic.advance(1, WART_BATCH, true, WART, 0, 1));
    }

    @Test
    @DisplayName("堆叠 64：纯推进 7 tick 完成（step=count，D11；含启动那一 tick 实际 8 tick，见 tech §5）")
    void stacked_completesInSevenTicks() {
        int brewTime = FULL;
        int ticks = 0;
        while (brewTime > 0) {
            var step = BrewingBatchLogic.advance(brewTime, WART_BATCH, true, WART, 0, 64);
            ticks++;
            if (step instanceof BrewingBatchLogic.Step.Complete) {
                brewTime = 0;
            } else {
                brewTime = ((BrewingBatchLogic.Step.Continue) step).brewTime();
            }
        }
        assertEquals(7, ticks);
    }

    // ---------------- 作废（D8：只有两条判据）----------------

    @Test
    @DisplayName("酿造中材料失效（不可酿造）⇒ 作废")
    void notBrewable_aborts() {
        assertInstanceOf(BrewingBatchLogic.Step.Abort.class,
            BrewingBatchLogic.advance(FULL, WART_BATCH, false, WART, 0, 1));
    }

    @Test
    @DisplayName("酿造中材料槽被清空（材料 Item 变 null）⇒ 作废")
    void ingredientCleared_aborts() {
        assertInstanceOf(BrewingBatchLogic.Step.Abort.class,
            BrewingBatchLogic.advance(FULL, WART_BATCH, true, null, 0, 1));
    }

    @Test
    @DisplayName("酿造中换成另一种材料 ⇒ 作废")
    void differentIngredient_aborts() {
        assertInstanceOf(BrewingBatchLogic.Step.Abort.class,
            BrewingBatchLogic.advance(FULL, WART_BATCH, true, GLOWSTONE, 0, 1));
    }

    @Test
    @DisplayName("材料【数量】变化不算换（D8：只比 Item）⇒ 继续酿造")
    void ingredientCountChange_continues() {
        // 状态机只拿到 Item，数量变化在调用侧不可见 ⇒ 同 Item 必继续
        var step = BrewingBatchLogic.advance(FULL, WART_BATCH, true, WART, 0, 1);
        assertInstanceOf(BrewingBatchLogic.Step.Continue.class, step);
    }

    @Test
    @DisplayName("完成那一 tick 不再检查材料变化（照抄原版 else-if 语义）⇒ 仍完成")
    void completingTick_ignoresIngredientChange() {
        assertInstanceOf(BrewingBatchLogic.Step.Complete.class,
            BrewingBatchLogic.advance(1, WART_BATCH, true, GLOWSTONE, 0, 1));
    }

    // ---------------- 堆叠加速（D11）----------------

    @Test
    @DisplayName("step = 堆叠数；单个 = 原版 400t；异常堆叠数兜底为 1")
    void stepFor_matchesStackCount() {
        assertEquals(1, BrewingBatchLogic.stepFor(1), "单个 = 原版速度");
        assertEquals(2, BrewingBatchLogic.stepFor(2));
        assertEquals(64, BrewingBatchLogic.stepFor(64));
        assertEquals(1, BrewingBatchLogic.stepFor(0), "空堆叠兜底为 1，避免除零 / 停滞");
    }

    @Test
    @DisplayName("堆叠只加速过程：每批耗时随堆叠缩短，但每批仍只扣 1 次燃料")
    void stacking_onlySpeedsUpProcess() {
        // 启动一次只产生一个 Start（⇒ 调用侧只扣 1 次燃料），与堆叠数无关
        for (int count : new int[] {1, 8, 64}) {
            var step = BrewingBatchLogic.advance(0, NO_BATCH, true, WART, FUEL,
                BrewingBatchLogic.stepFor(count));
            assertTrue(step instanceof BrewingBatchLogic.Step.Start,
                "堆叠 " + count + " 也应正常启动");
        }
    }

    // ---------------- 批次标记（白幕）----------------

    @Test
    @DisplayName("批次标记：Start 写当下 / Continue 保持 / Complete·Abort·Idle 一律收起（Idle = 自愈）")
    void batchMarkAfter_lifecycle() {
        long now = 1000L;

        assertEquals(now,
            BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Start(FULL, WART), -1L, now),
            "Start ⇒ 记下当下（客户端据此起播白幕）");

        assertEquals(777L,
            BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Continue(300), 777L, now),
            "进行中保持原标记 ⇒ 稳态零写入");

        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Complete(), 777L, now),
            "完成 ⇒ 收起");
        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Abort(), 777L, now),
            "作废 ⇒ 收起");

        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Idle(), 777L, now),
            "🔴 自愈回归：无批次却留着标记 ⇒ 必须收起。物品移出容器后运行时缓存作废（brewTime 归零）"
                + "⇒ 走不到 Complete/Abort ⇒ 标记残留 ⇒ 客户端白幕会在「没有进度」时继续播完一轮");

        assertEquals(-1L, BrewingBatchLogic.batchMarkAfter(new BrewingBatchLogic.Step.Idle(), -1L, now),
            "本来就是 -1 ⇒ 保持 -1（调用侧据此判定「无变化」⇒ 不写组件，稳态零写入）");
    }
}
