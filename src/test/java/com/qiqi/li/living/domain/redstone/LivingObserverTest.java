package com.qiqi.li.living.domain.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.ObservableStackState;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;

/** 活侦测器最终玩法：正面相邻槽语义快照 + 结算后采样 + 原版忙碌窗口。 */
class LivingObserverTest {

    private static final int SIZE = 27;
    private static final int WIDTH = 9;
    private static final int OBSERVER_SLOT = 10;
    private static final int TARGET_SLOT = 9;   // 输出 RIGHT ⇒ 正面 = LEFT
    private static final int OUTPUT_SLOT = 11;

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack observer(int count, Pos2D outputDirection) {
        ItemStack stack = living(Items.OBSERVER, count);
        LivingObserverData.set(stack, new LivingObserverData(outputDirection));
        return stack;
    }

    private static FakeContainerContext context() {
        return new FakeContainerContext(SIZE, WIDTH, "observer_" + UUID.randomUUID());
    }

    private static Map<String, Set<Integer>> slots(int observerSlot, Set<Integer> dustSlots) {
        return Map.of(
            LivingObserverFunction.ID, Set.of(observerSlot),
            LivingRedstoneFunction.ID, dustSlots);
    }

    private static void tick(ContainerRedstoneData data, FakeContainerContext ctx,
            Map<String, Set<Integer>> functionSlots) {
        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(functionSlots);
        data.resetProcessedFlag();
        data.calculate(ctx, tick);
    }

    private static void establishBaseline(ContainerRedstoneData data, FakeContainerContext ctx,
            Map<String, Set<Integer>> functionSlots) {
        tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT), "首次出现只建基线，不发脉冲");
    }

    @Test
    @DisplayName("首次放入只建立正面槽基线，不产生脉冲")
    void initialPlacement_establishesBaselineWithoutPulse() {
        var ctx = context();
        ctx.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);

        tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        assertEquals(0, data.getSignal(OUTPUT_SLOT));

        for (int i = 0; i < 5; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT), "静态目标不得在后续误触发");
    }

    @Test
    @DisplayName("只观察输出反方向的相邻槽；容器其它槽变化不触发")
    void observesOnlyOppositeAdjacentSlot() {
        var ctx = context();
        ctx.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);

        ctx.rawSet(20, new ItemStack(Items.DIRT, 4));
        for (int i = 0; i < 6; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT), "非正面槽变化不得触发");
        assertEquals(0, data.getSignal(OUTPUT_SLOT));

        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 2));
        tick(data, ctx, functionSlots);  // N：检出，安排 DELAY(2)
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+1：delay 1
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+2：pulse 1
        assertTrue(data.observerPowered(OBSERVER_SLOT));
        assertEquals(15, data.getSignal(OUTPUT_SLOT));
        tick(data, ctx, functionSlots);  // N+3：pulse 2
        assertTrue(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+4：idle
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        assertEquals(0, data.getSignal(OUTPUT_SLOT));
    }

    @Test
    @DisplayName("基础粒度：物品类型、数量、活化身份变化触发；未知组件变化不触发")
    void genericObservableGranularity() {
        var ctx = context();
        ctx.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);

        // 未声明的组件（自定义名）不进入基础语义。
        ctx.getItem(TARGET_SLOT).set(
            net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("renamed"));
        for (int i = 0; i < 5; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT));

        // 类型改变触发。
        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.DIRT, 1));
        assertPulse(data, ctx, functionSlots);

        // 活化身份改变触发（同 item / count）。
        ItemStack livingDirt = new ItemStack(Items.DIRT, 1);
        LivingItemManager.setLiving(livingDirt, true);
        ctx.rawSet(TARGET_SLOT, livingDirt);
        assertPulse(data, ctx, functionSlots);
    }

    @Test
    @DisplayName("忙碌窗口严格丢弃：延迟/脉冲变化只追平，恢复后不补发")
    void busyWindowChangesAreDropped() {
        var ctx = context();
        ctx.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);

        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 2));
        tick(data, ctx, functionSlots);     // 受理 DELAY(2)
        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 3));
        tick(data, ctx, functionSlots);     // 忙碌：丢弃并追平
        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 4));
        tick(data, ctx, functionSlots);     // PULSE 1，仍丢弃
        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 5));
        tick(data, ctx, functionSlots);     // PULSE 2，仍丢弃
        tick(data, ctx, functionSlots);     // 结束拍也属忙碌，只追平
        assertFalse(data.observerPowered(OBSERVER_SLOT));

        for (int i = 0; i < 6; i++) {
            tick(data, ctx, functionSlots);
            assertFalse(data.observerPowered(OBSERVER_SLOT),
                "忙碌期最终状态不得在恢复后补发（tick " + i + "）");
        }

        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 6));
        assertPulse(data, ctx, functionSlots);
    }

    @Test
    @DisplayName("改向只重建新目标基线，不把目标切换本身当变化")
    void directionChangeRebaselinesWithoutPulse() {
        var ctx = context();
        ctx.set(9, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(1, new ItemStack(Items.DIRT, 8));   // 输出 DOWN 时正面 = UP
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);

        LivingObserverData.set(ctx.getItem(OBSERVER_SLOT), new LivingObserverData(Pos2D.DOWN));
        for (int i = 0; i < 5; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT), "改向只建新基线");

        ctx.rawSet(1, new ItemStack(Items.DIRT, 9));
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);
        assertTrue(data.observerPowered(OBSERVER_SLOT), "新正面槽变化应触发");
    }

    @Test
    @DisplayName("拆合堆叠只重建基线，不继承旧脉冲；堆叠仅影响输出 cap")
    void splitMergeRebaselinesAndCountControlsCap() {
        var ctx = context();
        ctx.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 64));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);

        ctx.getItem(OBSERVER_SLOT).setCount(4);
        for (int i = 0; i < 5; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT), "count 改变只重建基线");

        ctx.rawSet(TARGET_SLOT, new ItemStack(Items.STONE, 2));
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);
        assertTrue(data.observerPowered(OBSERVER_SLOT));
        assertEquals(16, data.getSignal(OUTPUT_SLOT), "4 个侦测器 cap=4²=16");
    }

    @Test
    @DisplayName("红石公开状态可被侦测；按钮内部 pulseTimer 变化不制造额外事件")
    void redstoneSemanticStateIsObservableButTimerIsNot() {
        var ctx = context();
        ItemStack button = living(Items.STONE_BUTTON, 1);
        ctx.set(TARGET_SLOT, button);
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = Map.<String, Set<Integer>>of(
            LivingButtonFunction.ID, Set.of(TARGET_SLOT),
            LivingObserverFunction.ID, Set.of(OBSERVER_SLOT),
            LivingRedstoneFunction.ID, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);

        LivingButtonData.set(button, new LivingButtonData(true, 20, false));
        assertPulse(data, ctx, functionSlots);  // pressed / settled output 变化

        // 脉冲结束后仍 pressed；只改内部 timer，公开状态不变，不触发。
        LivingButtonData.set(button, new LivingButtonData(true, 19, false));
        for (int i = 0; i < 6; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(OBSERVER_SLOT));
    }

    @Test
    @DisplayName("正面越界安全不工作；随后改向有效目标只建基线")
    void outOfBoundsTargetDoesNotPulse() {
        var ctx = context();
        ctx.set(0, observer(1, Pos2D.DOWN));  // 正面 = UP，越界
        var functionSlots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(0));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);

        for (int i = 0; i < 8; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(0));

        ctx.set(1, new ItemStack(Items.STONE, 1));
        LivingObserverData.set(ctx.getItem(0), new LivingObserverData(Pos2D.LEFT));
        for (int i = 0; i < 4; i++) tick(data, ctx, functionSlots);
        assertFalse(data.observerPowered(0), "有效目标首次出现只建基线");
    }

    @Test
    @DisplayName("可观测值按功能 ID 稳定聚合并深拷贝标签")
    void observableStateIsStableAndDefensivelyCopied() {
        var ctx = context();
        ItemStack lever = living(Items.LEVER, 1);
        ctx.set(TARGET_SLOT, lever);
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        var slots = Map.<String, Set<Integer>>of(LivingLeverFunction.ID, Set.of(TARGET_SLOT));
        tick(data, ctx, slots);

        ObservableStackState first = com.qiqi.li.living.api.LivingItemFunction.captureObservableState(
            lever, TARGET_SLOT, ctx);
        assertEquals(Items.LEVER.builtInRegistryHolder().key().location(), first.itemId());
        assertTrue(first.living());
        ObservableStackState.FunctionState leverState = first.functions().stream()
            .filter(state -> LivingLeverFunction.ID.equals(state.functionId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("应包含 living_lever 公开状态"));

        CompoundTag leaked = leverState.state();
        leaked.putBoolean("powered", true);
        ObservableStackState second = com.qiqi.li.living.api.LivingItemFunction.captureObservableState(
            lever, TARGET_SLOT, ctx);
        assertEquals(first, second, "取出的标签副本不得修改已捕获状态");

        LivingLeverData.set(lever, new LivingLeverData(true));
        ObservableStackState changed = com.qiqi.li.living.api.LivingItemFunction.captureObservableState(
            lever, TARGET_SLOT, ctx);
        assertNotEquals(first, changed, "公开 powered 状态变化必须可见");
    }

    @Test
    @DisplayName("结算后采样：同拍新产生的红石粉最终出边在下一拍触发侦测")
    void samplesSettledRedstoneOutput() {
        var ctx = context();
        ItemStack targetDust = living(Items.REDSTONE, 1);
        ctx.set(TARGET_SLOT, targetDust);
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(18, living(Items.LEVER, 1));
        var functionSlots = Map.<String, Set<Integer>>of(
            LivingLeverFunction.ID, Set.of(18),
            LivingObserverFunction.ID, Set.of(OBSERVER_SLOT),
            LivingRedstoneFunction.ID, Set.of(TARGET_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(data, ctx, functionSlots);
        ObservableStackState before = com.qiqi.li.living.api.LivingItemFunction.captureObservableState(
            targetDust, TARGET_SLOT, ctx);

        LivingLeverData.set(ctx.getItem(18), new LivingLeverData(true));
        tick(data, ctx, functionSlots);  // 本拍先传播，再采到粉最终状态并安排延迟
        ObservableStackState after = com.qiqi.li.living.api.LivingItemFunction.captureObservableState(
            targetDust, TARGET_SLOT, ctx);
        assertNotEquals(before, after,
            "红石传播完成后，粉的 signal/connections/output 公开状态应改变");
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);
        assertTrue(data.observerPowered(OBSERVER_SLOT),
            "红石粉本拍结算后的 signal/connections/output 必须可被侦测");
    }

    @Test
    @DisplayName("多个侦测器先统一采样再更新，槽位迭代顺序不改变结果")
    void simultaneousObserversAreOrderIndependent() {
        var ctx = context();
        ctx.set(9, new ItemStack(Items.STONE, 1));
        ctx.set(10, observer(1, Pos2D.RIGHT));   // 看 9
        ctx.set(11, observer(1, Pos2D.RIGHT));   // 看 10（侦测器公开输出）
        var functionSlots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(11, 10));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        tick(data, ctx, functionSlots);

        ctx.rawSet(9, new ItemStack(Items.STONE, 2));
        tick(data, ctx, functionSlots);  // 10 安排延迟；11 采样时 10 尚未输出
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);  // 10 开始输出；11 在结算后采到输出变化
        assertTrue(data.observerPowered(10));
        assertFalse(data.observerPowered(11));
        tick(data, ctx, functionSlots);
        tick(data, ctx, functionSlots);  // 11 到脉冲
        assertTrue(data.observerPowered(11),
            "下游侦测器应响应上游的已结算输出，而非同循环半更新状态");
    }

    @Test
    @DisplayName("运行账本丢失只重建基线，不制造假脉冲")
    void runtimeLossRebaselinesWithoutPulse() {
        var ctx = context();
        ctx.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        var functionSlots = Map.<String, Set<Integer>>of(
            LivingObserverFunction.ID, Set.of(OBSERVER_SLOT));
        var original = com.qiqi.li.living.container.ContainerLivingItemHandler.getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);
        establishBaseline(original, ctx, functionSlots);

        var rebuilt = new ContainerRedstoneData();
        for (int i = 0; i < 6; i++) tick(rebuilt, ctx, functionSlots);
        assertFalse(rebuilt.observerPowered(OBSERVER_SLOT),
            "区块卸载/缓存回收后的新账本只建立当前基线");
    }

    @Test
    @DisplayName("movedSlots 增量刷新功能索引：旧槽删除、新槽按当前物品重建")
    void movedSlotsRefreshFunctionIndex() {
        var ctx = context();
        ctx.set(10, observer(1, Pos2D.RIGHT));
        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(Map.of(LivingObserverFunction.ID, Set.of(10)));

        ctx.rawSet(10, ItemStack.EMPTY);
        ctx.rawSet(12, observer(1, Pos2D.RIGHT));
        tick.movedSlots.add(10);
        tick.movedSlots.add(12);
        tick.refreshMovedFunctionSlots(ctx);

        assertEquals(Set.of(12), tick.getFunctionSlots(LivingObserverFunction.ID));
    }

    @Test
    @DisplayName("活塞本体 extended 翻转可被侦测；动画/信号等内部状态不在公开快照")
    void observesLivingPistonBodyState() {
        var ctx = context();
        ItemStack piston = living(Items.PISTON, 1);
        com.qiqi.li.living.domain.piston.LivingPistonData.set(piston,
            new com.qiqi.li.living.domain.piston.LivingPistonData(Pos2D.RIGHT));
        ctx.set(TARGET_SLOT, piston);
        ctx.set(OBSERVER_SLOT, observer(1, Pos2D.RIGHT));
        ctx.set(OUTPUT_SLOT, living(Items.REDSTONE, 1));
        var functionSlots = slots(OBSERVER_SLOT, Set.of(OUTPUT_SLOT));
        var data = com.qiqi.li.living.container.ContainerLivingItemHandler
            .getOrCreateContainerData(ctx, ContainerRedstoneData.KEY);

        try {
            com.qiqi.li.living.runtime.ContainerRuntimeCache.update(ctx, TARGET_SLOT,
                com.qiqi.li.living.runtime.RuntimeSegments.EMPTY.with(
                    com.qiqi.li.living.domain.piston.PistonSegment.INSTANCE,
                    com.qiqi.li.living.domain.piston.PistonRuntime.retracted(Pos2D.RIGHT)));
            establishBaseline(data, ctx, functionSlots);

            com.qiqi.li.living.runtime.ContainerRuntimeCache.update(ctx, TARGET_SLOT,
                com.qiqi.li.living.runtime.RuntimeSegments.EMPTY.with(
                    com.qiqi.li.living.domain.piston.PistonSegment.INSTANCE,
                    new com.qiqi.li.living.domain.piston.PistonRuntime(true, Pos2D.RIGHT)));
            assertPulse(data, ctx, functionSlots);

            com.qiqi.li.living.runtime.ContainerRuntimeCache.update(ctx, TARGET_SLOT,
                com.qiqi.li.living.runtime.RuntimeSegments.EMPTY.with(
                    com.qiqi.li.living.domain.piston.PistonSegment.INSTANCE,
                    com.qiqi.li.living.domain.piston.PistonRuntime.retracted(Pos2D.RIGHT)));
            assertPulse(data, ctx, functionSlots);
        } finally {
            com.qiqi.li.living.runtime.ContainerRuntimeCache.removeContainer(ctx.getCacheKey());
        }
    }

    @Test
    @DisplayName("方向配置只接受 direction 四向且同值不写")
    void directionConfigurationIsCardinalAndChangeAware() {
        ItemStack stack = observer(1, Pos2D.UP);
        LivingObserverFunction function = new LivingObserverFunction();

        assertFalse(function.updateSlotDirection(new ItemStack(Items.OBSERVER), "direction", Pos2D.RIGHT));
        assertFalse(function.updateSlotDirection(stack, "unknown", Pos2D.RIGHT));
        assertFalse(function.updateSlotDirection(stack, "direction", Pos2D.NONE));
        assertFalse(function.updateSlotDirection(stack, "direction", Pos2D.UP_RIGHT));
        assertFalse(function.updateSlotDirection(stack, "direction", Pos2D.UP));
        assertTrue(function.updateSlotDirection(stack, "direction", Pos2D.RIGHT));
        assertEquals(Pos2D.RIGHT, LivingObserverData.of(stack).direction());
    }

    private static void assertPulse(ContainerRedstoneData data, FakeContainerContext ctx,
            Map<String, Set<Integer>> functionSlots) {
        tick(data, ctx, functionSlots);  // N detect
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+1 delay
        assertFalse(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+2 pulse
        assertTrue(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+3 pulse
        assertTrue(data.observerPowered(OBSERVER_SLOT));
        tick(data, ctx, functionSlots);  // N+4 idle
        assertFalse(data.observerPowered(OBSERVER_SLOT));
    }
}
