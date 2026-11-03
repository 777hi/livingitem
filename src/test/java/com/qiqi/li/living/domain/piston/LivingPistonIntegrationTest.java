package com.qiqi.li.living.domain.piston;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.hopper.DirectionTransferData;
import com.qiqi.li.living.domain.hopper.LivingHopperData;
import com.qiqi.li.living.domain.ender.EnderChannelKey;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegmentType;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 活活塞 **processContext 集成**端到端 —— 走真实
 * {@code ContainerLivingItemHandler.processContext}，验证注册 → 扫描 → tick → runtime 写回
 * → movedSlots 过滤的全链路。信号由测试内 mock sensor 控制（红石传播已有 20+ 例覆盖
 * {@code ContainerRedstoneDataTest}；此处 mock 是为了把失败点归一到活塞侧——
 * 同 {@code TntRedstoneIgniteTest} 的做法）。
 *
 * <p>布局（1×9 单行，方向 RIGHT）：{@code [0][1][2][3][活塞 4][货 5][空 6][7][8]}。</p>
 *
 * <p>⚠️ {@link ContainerRuntimeCache} 是静态全局 —— @AfterEach 清空，
 * 否则前一个用例的 extended=true 会让下一个用例的活塞「以为自己在伸出」而不推。</p>
 */
class LivingPistonIntegrationTest {

    private static final int PISTON = 4, CARGO = 5, PAST = 6;
    private static final RuntimeSegmentType<Integer> CARGO_MARKER = new RuntimeSegmentType<>() {
        @Override public String id() { return "piston_test_cargo_marker"; }
        @Override public StreamCodec<? super RegistryFriendlyByteBuf, Integer> codec() {
            return ByteBufCodecs.INT;
        }
    };
    private static final CargoObserver CARGO_OBSERVER = new CargoObserver();

    @BeforeAll
    static void registerCargoObserver() {
        LivingItemManager.registerFunction(CARGO_OBSERVER);
    }

    private static final class CargoObserver implements LivingItemFunction, HasContainerData {
        private ItemStack observed;
        private ItemStack containerObserved;
        private RuntimeSegments runtime;
        private RuntimeSegments containerRuntime;
        private boolean moved;

        @Override public boolean canApply(ItemStack stack) {
            return stack.is(Items.DIAMOND) && LivingItemManager.isLivingItem(stack);
        }
        @Override public String getFunctionId() { return "test_piston_cargo_observer"; }
        @Override public int getPriority() { return 99; }
        @Override public void tick(List<SlotEntry> entries, com.qiqi.li.living.container.ContainerContext context,
                                   TickContext tick, Level level) {
            if (entries.isEmpty()) return;
            SlotEntry entry = entries.getFirst();
            observed = entry.stack();
            runtime = ContainerRuntimeCache.get(context, entry.slotIndex());
            moved = tick.movedSlots.contains(entry.slotIndex());
        }
        @Override public void tickContainerData(List<SlotEntry> entries,
                com.qiqi.li.living.container.ContainerContext context, TickContext tick) {
            if (entries.isEmpty()) return;
            SlotEntry entry = entries.getFirst();
            containerObserved = entry.stack();
            containerRuntime = ContainerRuntimeCache.get(context, entry.slotIndex());
        }
        void reset() {
            observed = null;
            containerObserved = null;
            runtime = null;
            containerRuntime = null;
            moved = false;
        }
    }

    private static volatile int signal = 0;

    /** 模拟真实 IItemHandler 语义：insert 空槽全额收 / 同类合并 / 异类拒（canAcceptAll 预检依赖）。 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots = new ItemStack[9];

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ItemStack existing = getStackInSlot(slot);
            if (existing.isEmpty()) {
                int accepted = Math.min(stack.getCount(), getSlotLimit(slot));
                if (!simulate && accepted > 0) slots[slot] = stack.copyWithCount(accepted);
                return stack.copyWithCount(stack.getCount() - accepted);
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = Math.min(getSlotLimit(slot), existing.getMaxStackSize()) - existing.getCount();
                int accepted = Math.min(stack.getCount(), Math.max(0, space));
                if (!simulate && accepted > 0) existing.grow(accepted);
                return stack.copyWithCount(stack.getCount() - accepted);
            }
            return stack;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = getStackInSlot(slot);
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) {
                slots[slot] = toExtract >= existing.getCount()
                    ? ItemStack.EMPTY : existing.copyWithCount(existing.getCount() - toExtract);
            }
            return result;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private FakeHandler handler;
    private SimpleContainerContext ctx;
    private final Level level = org.mockito.Mockito.mock(Level.class);

    private void mockServer() {
        org.mockito.Mockito.when(level.getServer())
            .thenReturn(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
    }

    private void setup(boolean sticky) {
        registerMockSensor();
        mockServer();
        signal = 0;
        handler = new FakeHandler();
        handler.slots[PISTON] = livingPiston(sticky);
        handler.slots[CARGO] = new ItemStack(Items.DIRT, 7);
        ctx = new SimpleContainerContext(handler, level);
    }

    /** 替换 sensor resolver：信号由本测试的静态 field 控制。@AfterEach 恢复生产 resolver。 */
    private void registerMockSensor() {
        TickContext.registerSensorResolver(ctx -> new RedstoneSensor() {
            @Override public int sensedSignal(int slot, int dir) { return signal; }
            @Override public int prevSensedSignal(int slot, int dir) { return signal; }
            @Override public int maxSensedSignal(int slot) { return signal; }
        });
    }

    private static ItemStack livingPiston(boolean sticky) {
        ItemStack s = new ItemStack(sticky ? Items.STICKY_PISTON : Items.PISTON);
        LivingItemManager.setLiving(s, true);
        LivingPistonData.set(s, new LivingPistonData(com.qiqi.li.living.model.Pos2D.RIGHT));
        return s;
    }

    private static ItemStack livingHopper(int count, Pos2D source, Pos2D target) {
        ItemStack s = new ItemStack(Items.HOPPER, count);
        LivingItemManager.setLiving(s, true);
        LivingHopperData.set(s, LivingHopperData.DEFAULT.withDirection(new DirectionTransferData(source, target)));
        return s;
    }

    private static ItemStack routeEnderChest(int count) {
        ItemStack s = new ItemStack(Items.ENDER_CHEST, count);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    /** 跑 n 拍真实 processContext（信号由静态 field 控制）。 */
    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            ContainerLivingItemHandler.processContext(ctx, level);
        }
    }

    private boolean extended() {
        return runtimeAt(PISTON).extended();
    }

    private com.qiqi.li.living.domain.piston.PistonRuntime runtimeAt(int slot) {
        var state = ContainerRuntimeCache.get(ctx, slot).get(PistonSegment.INSTANCE);
        assertTrue(state != null, "slot " + slot + " 应有活塞 runtime 片段");
        return state;
    }

    private int total() {
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += handler.slots[i] != null ? handler.slots[i].getCount() : 0;
        return sum;
    }

    @AfterEach
    void cleanRuntimeAndSignal() {
        ContainerRuntimeCache.clear();
        EnderChannelRegistry.getInstance().clearAll();
        CARGO_OBSERVER.reset();
        signal = 0;
        // 恢复生产 resolver（RedstoneRegistration 注册的同款）
        TickContext.registerSensorResolver(
            ctx -> ctx.getOrCreateContainerData(com.qiqi.li.living.domain.redstone.ContainerRedstoneData.KEY));
    }

    // ════════════════════════════════════════
    // 1. 通电推 + 持续通电保持
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：信号供电 ⇒ 整条链前移一格，持续通电保持不重推")
    void poweredPistonPushesThenHolds() {
        setup(false);
        signal = 15;
        ticks(2);

        assertTrue(extended(), "供电后活塞必已伸出");
        assertTrue(handler.slots[CARGO].isEmpty(), "货已离开原槽");
        assertEquals(7, handler.slots[PAST].getCount(), "货到达 X+2");
        assertEquals(Items.PISTON, handler.slots[PISTON].getItem(), "活塞本体不动");
        assertEquals(8, total(), "总量守恒（活塞1 + 货7）");

        ticks(3);
        assertEquals(7, handler.slots[PAST].getCount(), "持续通电不重推");
        assertTrue(extended(), "保持伸出");
    }

    // ════════════════════════════════════════
    // 2. 普通活塞断电收回（不拉货）
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：普通活塞断电 ⇒ 收回，货留在 X+2 不被拉回")
    void ordinaryPistonRetractsOnPowerLoss() {
        setup(false);
        signal = 15;
        ticks(2);
        assertTrue(extended(), "前置：已伸出");

        signal = 0;
        ticks(2);

        assertFalse(extended(), "断电收回");
        assertEquals(7, handler.slots[PAST].getCount(), "普通活塞不拉货");
        assertTrue(handler.slots[CARGO].isEmpty());
        assertEquals(8, total());
    }

    // ════════════════════════════════════════
    // 3. 粘性活塞断电拉回
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：粘性活塞断电 ⇒ X+2 货整栈拉回 X+1")
    void stickyPistonPullsOnPowerLoss() {
        setup(true);
        signal = 15;
        ticks(2);
        assertTrue(extended(), "前置：已伸出");
        assertEquals(7, handler.slots[PAST].getCount());

        signal = 0;
        ticks(2);

        assertFalse(extended(), "断电收回");
        assertEquals(7, handler.slots[CARGO].getCount(), "粘性拉：货回 X+1");
        assertTrue(handler.slots[PAST].isEmpty(), "X+2 清空");
        assertEquals(8, total());
    }

    // ════════════════════════════════════════
    // 4. 取消活化清组件（注册后 clearLivingData 首次真生效）
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：取消活化 ⇒ 方向组件与 IS_LIVING 都被清（无孤儿数据）")
    void deactivationClearsPistonComponents() {
        setup(false);
        var stack = ctx.getItem(PISTON);
        assertTrue(stack.has(PistonComponents.LIVING_PISTON_DATA.value()), "前置：方向组件在");
        assertTrue(LivingItemManager.isLivingItem(stack));

        LivingItemManager.setLiving(stack, false);

        assertFalse(stack.has(PistonComponents.LIVING_PISTON_DATA.value()),
            "LIVING_PISTON_DATA 已由 LivingPistonFunction 声明归属，取消活化必须清除");
        assertFalse(stack.has(LivingComponents.IS_LIVING.value()));
    }

    // ════════════════════════════════════════
    // 5. 同容器双活塞独立 tick
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：同容器两台活塞各自独立推（一条 pass 内逐 entry 处理）")
    void twoPistonsTickIndependently() {
        registerMockSensor();
        mockServer();
        signal = 15;
        handler = new FakeHandler();
        handler.slots[1] = livingPiston(false);
        handler.slots[2] = new ItemStack(Items.DIRT, 7);
        handler.slots[5] = livingPiston(true);
        handler.slots[6] = new ItemStack(Items.IRON_INGOT, 3);
        ctx = new SimpleContainerContext(handler, level);

        ticks(2);

        assertTrue(handler.slots[2].isEmpty() && handler.slots[3].getCount() == 7,
            "活塞 1 推货 2→3");
        assertTrue(handler.slots[6].isEmpty() && handler.slots[7].getCount() == 3,
            "活塞 2 推货 6→7");
        assertTrue(runtimeAt(1).extended(), "活塞 1 伸出");
        assertTrue(runtimeAt(5).extended(), "活塞 2 伸出");
    }

    // ════════════════════════════════════════
    // 6. 链长超限全失败
    // ════════════════════════════════════════

    // ════════════════════════════════════════
    // 7. N+1 活漏斗机械压送（G2）
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：N+1 活漏斗压送，q=min(C,M,A)，部分交付后方不动")
    void pressureDeliveryUsesActualQuantityAndLeavesRearCargoStill() {
        registerMockSensor();
        mockServer();
        signal = 15;
        handler = new FakeHandler() {
            @Override public int getSlotLimit(int slot) { return slot == 7 ? 2 : 64; }
        };
        handler.slots[4] = livingPiston(false);
        handler.slots[5] = new ItemStack(Items.DIAMOND, 7);      // N=1 的末端货
        handler.slots[6] = livingHopper(3, Pos2D.LEFT, Pos2D.RIGHT); // N+1 接收者
        ctx = new SimpleContainerContext(handler, level);

        ticks(2);

        assertTrue(runtimeAt(4).extended(), "实际 q=2 >0 ⇒ 活塞伸出");
        assertEquals(5, handler.slots[5].getCount(), "C=7、M=3、A=2 ⇒ 留5");
        assertEquals(2, handler.slots[7].getCount(), "出口实际收2");
        assertEquals(3, handler.slots[6].getCount(), "接收漏斗本体不动");
    }

    @Test
    @DisplayName("processContext：部分压送后，后续功能读当前栈且同槽 runtime 不失效")
    void partialPressureRefreshesLaterEntryWithoutInvalidatingRuntime() {
        registerMockSensor();
        mockServer();
        signal = 15;
        CARGO_OBSERVER.reset();
        handler = new FakeHandler() {
            @Override public int getSlotLimit(int slot) { return slot == 7 ? 2 : 64; }
        };
        handler.slots[4] = livingPiston(false);
        handler.slots[5] = new ItemStack(Items.DIAMOND, 7);
        LivingItemManager.setLiving(handler.slots[5], true);
        ItemStack scanned = handler.slots[5];
        handler.slots[6] = livingHopper(3, Pos2D.LEFT, Pos2D.RIGHT);
        ctx = new SimpleContainerContext(handler, level);
        RuntimeSegments marker = RuntimeSegments.EMPTY.with(CARGO_MARKER, 42);
        ContainerRuntimeCache.update(ctx, 5, marker);

        ticks(1);

        assertEquals(5, handler.slots[5].getCount());
        assertTrue(CARGO_OBSERVER.observed != null, "后续功能必须仍执行");
        assertSame(handler.slots[5], CARGO_OBSERVER.observed, "功能 tick 的 SlotEntry 必须刷新为当前槽对象");
        assertSame(handler.slots[5], CARGO_OBSERVER.containerObserved,
            "容器级数据阶段的 SlotEntry 也必须刷新为当前槽对象");
        assertTrue(CARGO_OBSERVER.observed != scanned, "不得继续使用扫描期 count=7 的旧对象");
        assertFalse(CARGO_OBSERVER.moved, "同槽数量变化不是机械位移");
        assertSame(marker, CARGO_OBSERVER.runtime, "功能 tick 不得把同槽 runtime 作废");
        assertSame(marker, CARGO_OBSERVER.containerRuntime, "容器级数据阶段也应读到原 runtime");
    }

    @Test
    @DisplayName("processContext：出口满 q=0 ⇒ 不伸出，持续供电可重试")
    void blockedPressureRetriesUnderContinuousPower() {
        registerMockSensor();
        mockServer();
        signal = 15;
        handler = new FakeHandler();
        handler.slots[4] = livingPiston(false);
        handler.slots[5] = new ItemStack(Items.DIAMOND, 3);
        handler.slots[6] = livingHopper(3, Pos2D.LEFT, Pos2D.RIGHT);
        handler.slots[7] = new ItemStack(Items.STONE, 64);
        ctx = new SimpleContainerContext(handler, level);

        ticks(2);
        assertFalse(runtimeAt(4).extended());
        assertEquals(3, handler.slots[5].getCount());

        ctx.setItem(7, ItemStack.EMPTY);
        ticks(1);
        assertTrue(runtimeAt(4).extended(), "同一高电平下出口疏通 ⇒ 重试成功");
        assertTrue(handler.slots[5].isEmpty());
        assertEquals(3, handler.slots[7].getCount());
    }

    @Test
    @DisplayName("processContext：末端送空仍不补位，后方货始终原位")
    void emptyTerminalDoesNotBackfillRearCargo() {
        registerMockSensor();
        mockServer();
        signal = 15;
        handler = new FakeHandler();
        handler.slots[3] = livingPiston(false);
        handler.slots[3].setCount(2);                          // N=2
        handler.slots[4] = new ItemStack(Items.DIRT, 9);          // 后方货（N=2 第一节）
        handler.slots[5] = new ItemStack(Items.DIAMOND, 2);       // 原末端（N=2 第二节）
        handler.slots[6] = livingHopper(3, Pos2D.LEFT, Pos2D.RIGHT);
        ctx = new SimpleContainerContext(handler, level);

        ticks(2);

        assertTrue(runtimeAt(3).extended());
        assertEquals(9, handler.slots[4].getCount(), "后方货不补位");
        assertTrue(handler.slots[5].isEmpty(), "末端送空留空");
        assertEquals(2, handler.slots[7].getCount());
    }

    @Test
    @DisplayName("processContext：路由末影出口 q=0 ⇒ 不伸出、不扣货、不登记路由")
    void routeEnderTargetRejectsWithoutPistonSuccessOrRegistration() {
        registerMockSensor();
        mockServer();
        signal = 15;
        handler = new FakeHandler();
        handler.slots[4] = livingPiston(false);
        handler.slots[5] = new ItemStack(Items.DIAMOND, 3);
        handler.slots[6] = livingHopper(3, Pos2D.LEFT, Pos2D.RIGHT);
        handler.slots[7] = routeEnderChest(2);
        ctx = new SimpleContainerContext(handler, level);

        ticks(2);

        assertFalse(runtimeAt(4).extended());
        assertEquals(3, handler.slots[5].getCount());
        assertTrue(EnderChannelRegistry.getInstance().peek(EnderChannelKey.publicChannel(2)) == null);
    }

    // ════════════════════════════════════════
    // 8. 现有普通链超限
    // ════════════════════════════════════════

    @Test
    @DisplayName("processContext：链长超堆叠数 ⇒ 全失败不部分推")
    void oversizedChainFailsEntirely() {
        setup(false);
        signal = 15;
        handler.slots[PAST] = new ItemStack(Items.IRON_INGOT, 3);   // 链长 2 > N=1

        ticks(2);

        assertFalse(extended(), "推不动 ⇒ 不伸出");
        assertEquals(7, handler.slots[CARGO].getCount(), "货物原地");
        assertEquals(3, handler.slots[PAST].getCount(), "后方货也原地");
    }
}
