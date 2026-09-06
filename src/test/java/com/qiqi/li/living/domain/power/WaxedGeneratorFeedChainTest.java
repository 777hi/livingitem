package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.domain.redstone.LivingLeverFunction;
import com.qiqi.li.living.domain.redstone.LivingLeverData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneFunction;
import com.qiqi.li.living.domain.redstone.RedstoneSnapshotProvider;

/**
 * 涂蜡发电机「喂电链」分段回归：振荡信号从信号源出发，经过中间红石元件，
 * 每一跳都要能到达涂蜡槽的入边并驱动发电。
 *
 * <p>背景（v19.1 游戏内实测）：拉杆直连涂蜡的 E2E 已通过，但玩家实际搭建
 * 「振荡粉线贴着涂蜡铜块」仍不发电——本测试把链路逐段复刻，定位断点。</p>
 */
class WaxedGeneratorFeedChainTest {

    private static final int SIZE = 9;

    private static final LivingWaxedCopperFunction function = new LivingWaxedCopperFunction();

    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        FakeHandler(ItemStack... slots) { this.slots = slots; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack s = new ItemStack(item, count);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    /** 单测试场景句柄 */
    private record Scenario(SimpleContainerContext ctx, ContainerPowerData power,
                            ItemStack lever, int genSlot) {}

    /**
     * 布局：拉杆槽 0，粉链槽 1..dustLen，涂蜡发电机槽 1+dustLen。
     * 拉杆 4t 方波振荡；每 tick 走真实红石传播 + 电力层全链路。
     */
    private static Scenario runChain(int dustLen, int ticks) {
        ItemStack[] slots = new ItemStack[SIZE];
        ItemStack lever = living(Items.LEVER, 1);
        slots[0] = lever;
        for (int i = 1; i <= dustLen; i++) {
            slots[i] = living(Items.REDSTONE, 1);
        }
        int genSlot = 1 + dustLen;
        ItemStack gen = living(Items.WAXED_COPPER_BLOCK, 4);
        slots[genSlot] = gen;

        IItemHandler handler = new FakeHandler(slots);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        // 红石功能槽位（拉杆 + 粉链；涂蜡是绝缘体不参与信号层）
        var redSlots = new java.util.HashMap<String, java.util.Set<Integer>>();
        redSlots.put(LivingLeverFunction.ID, java.util.Set.of(0));
        redSlots.put(LivingRedstoneFunction.ID,
            java.util.Set.copyOf(range(1, dustLen)));

        // 电力层 entries（全部物品都要进框架 tick：粉驱动 calculate，涂蜡驱动发电）
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        for (int i = 0; i <= genSlot; i++) {
            entries.add(new LivingItemFunction.SlotEntry(i, slots[i]));
        }

        for (int t = 0; t < ticks; t++) {
            boolean powered = (Math.floorMod(t, 4) < 2);
            LivingItemManager.setLeverData(lever, new LivingLeverData(powered));
            ctx.syncSlotToClients(0, lever);

            TickContext tick = new TickContext(ctx);
            tick.setFunctionSlots(redSlots);
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, tick);

            function.tickContainerData(entries, ctx, tick);
        }
        return new Scenario(ctx, ctx.getOrCreatePowerData(), lever, genSlot);
    }

    private static List<Integer> range(int from, int len) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < len; i++) out.add(from + i);
        return out;
    }

    @Test
    @DisplayName("链路：拉杆 → 粉 → 涂蜡发电机（粉线中继，非直连）应锁相 4t 并发电")
    void chain_leverToDustToGenerator_powers() {
        Scenario s = runChain(2, 48);   // 拉杆(0) → 粉(1) → 粉(2) → 涂蜡(3)

        GeneratorState gen = s.power.getGenerator(s.genSlot());
        assertTrue(gen != null, "应有发电机状态");
        assertEquals(4, gen.channel().bestPeriod(4), "发电机应锁相 4t（拉杆方波上升沿间隔）");
        assertTrue(gen.getEmaPowerRe() > 0,
            "隔粉线的涂蜡发电机应发电，实际=" + (gen == null ? 0 : gen.getEmaPowerRe()));
    }

    @Test
    @DisplayName("对照：拉杆直连涂蜡（无粉线）应发电（既有 E2E 的对齐复刻）")
    void chain_directLever_powers() {
        Scenario s = runChain(0, 48);   // 拉杆(0) → 涂蜡(1)

        GeneratorState gen = s.power.getGenerator(s.genSlot());
        assertTrue(gen != null, "应有发电机状态");
        assertEquals(4, gen.channel().bestPeriod(4), "发电机应锁相 4t");
        assertTrue(gen.getEmaPowerRe() > 0, "直连涂蜡发电机应发电");
    }

    /**
     * 游戏场景完整复刻：活红石火把环自振（上一轮回归的同一布局），
     * 涂蜡发电机贴在环的输出粉旁。断言三段：环振荡、入边振荡、发电机锁相发电。
     */
    @Test
    @DisplayName("火把环自振 + 相邻涂蜡发电机：入边振荡应被感知并发电")
    void ring_torchOscillatorFeedsGenerator() {
        ItemStack[] slots = new ItemStack[27];
        // 3×3 火把环（左下角）：火把 (1,1)=10 输入=左，粉环 9/11/18/19/20
        ItemStack torch = living(Items.REDSTONE_TORCH, 1);
        LivingItemManager.setRedstoneTorchData(torch,
            LivingItemManager.getRedstoneTorchData(torch).withDirection(com.qiqi.li.living.model.Pos2D.RIGHT));
        slots[10] = torch;
        for (int idx : new int[] {9, 11, 18, 19, 20}) {
            slots[idx] = living(Items.REDSTONE, 1);
        }
        // 发电机贴在环输出粉 (1,2)=11 的右侧：(1,3)=12
        int genSlot = 12;
        slots[genSlot] = living(Items.WAXED_COPPER_BLOCK, 4);

        IItemHandler handler = new FakeHandler(slots);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        var redSlots = new java.util.HashMap<String, java.util.Set<Integer>>();
        redSlots.put(com.qiqi.li.living.domain.redstone.LivingRedstoneTorchFunction.ID, java.util.Set.of(10));
        redSlots.put(LivingRedstoneFunction.ID, java.util.Set.of(9, 11, 18, 19, 20));

        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(genSlot, slots[genSlot]));

        boolean sawEdgeHigh = false;
        boolean sawEdgeLow = false;
        for (int t = 0; t < 48; t++) {
            TickContext tick = new TickContext(ctx);
            tick.setFunctionSlots(redSlots);
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, tick);

            // 诊断：发电机左向入边（= 粉 11 朝右的出边）是否振荡
            int incoming = redstone.getIncomingEdgeValue(genSlot,
                ContainerRedstoneData.EDGE_LEFT);
            if (incoming > 0) sawEdgeHigh = true;
            else sawEdgeLow = true;

            function.tickContainerData(entries, ctx, tick);
        }

        assertTrue(sawEdgeHigh && sawEdgeLow,
            "发电机入边应随火把环振荡（观察到 high=" + sawEdgeHigh + " low=" + sawEdgeLow + "）");

        GeneratorState gen = ctx.getOrCreatePowerData().getGenerator(genSlot);
        assertTrue(gen != null, "应有发电机状态");
        assertEquals(2, gen.channel().bestPeriod(4), "火把环逐 tick 翻转 → 2t 方波，应锁相 2t");
        assertTrue(gen.getEmaPowerRe() > 0, "贴环涂蜡发电机应发电");
    }

    /**
     * 生产路径全链路：不手动调 calculate / 不手动注册功能槽位 / 不手动构建 entries，
     * 直接走 {@link ContainerLivingItemHandler#processContext}（真实扫描 → 分组 →
     * functionSlots → 优先级调度 → 红石 calculate → 电力层发电）。
     * 消除测试 harness 与生产的全部差异。
     */
    @Test
    @DisplayName("生产路径（processContext）：火把环 + 涂蜡发电机应发电")
    void productionPath_processContext_powersGenerator() {
        ItemStack[] slots = new ItemStack[27];
        ItemStack torch = living(Items.REDSTONE_TORCH, 1);
        LivingItemManager.setRedstoneTorchData(torch,
            LivingItemManager.getRedstoneTorchData(torch).withDirection(com.qiqi.li.living.model.Pos2D.RIGHT));
        slots[10] = torch;
        for (int idx : new int[] {9, 11, 18, 19, 20}) {
            slots[idx] = living(Items.REDSTONE, 1);
        }
        int genSlot = 12;
        slots[genSlot] = living(Items.WAXED_COPPER_BLOCK, 4);

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        for (int t = 0; t < 48; t++) {
            ContainerLivingItemHandler.processContext(ctx, null);
        }

        GeneratorState gen = ctx.getOrCreatePowerData().getGenerator(genSlot);
        assertTrue(gen != null, "应有发电机状态");
        assertEquals(2, gen.channel().bestPeriod(4), "生产路径下应锁相 2t");
        assertTrue(gen.getEmaPowerRe() > 0, "生产路径下应发电");
    }

    /**
     * 大箱子形状复刻：54 槽（9 列 × 6 行），振荡器与发电机同放左半箱（槽位 0-26）
     * 与跨半箱（交界两侧）两种摆法，走生产路径 processContext。
     */
    @Test
    @DisplayName("大箱子形状（54 槽）：火把环 + 相邻涂蜡发电机（生产路径）应发电")
    void doubleChestShape_powersGenerator() {
        ItemStack[] slots = new ItemStack[54];
        // 3×3 火把环放在左半箱中部：行 1 列 1-3（idx 10,11,12 一行 + 下行 19,20,21）
        ItemStack torch = living(Items.REDSTONE_TORCH, 1);
        LivingItemManager.setRedstoneTorchData(torch,
            LivingItemManager.getRedstoneTorchData(torch).withDirection(com.qiqi.li.living.model.Pos2D.RIGHT));
        // 环：火把 (1,1)=10 输入=左 (1,0)=9，输出右 (1,2)=11 → 下 (2,2)=20 → 左 (2,1)=19 → 左 (2,0)=18 → 上回 (1,0)=9
        slots[10] = torch;
        for (int idx : new int[] {9, 11, 18, 19, 20}) {
            slots[idx] = living(Items.REDSTONE, 1);
        }
        // 发电机贴在环输出延长线上：(1,3)=13（粉 11→12→发电机）
        int genSlot = 13;
        slots[genSlot] = living(Items.WAXED_COPPER_BLOCK, 4);
        slots[12] = living(Items.REDSTONE, 1);

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        for (int t = 0; t < 48; t++) {
            ContainerLivingItemHandler.processContext(ctx, null);
        }

        GeneratorState gen = ctx.getOrCreatePowerData().getGenerator(genSlot);
        assertTrue(gen != null, "应有发电机状态");
        assertTrue(gen.getEmaPowerRe() > 0, "大箱形状下涂蜡发电机应发电");
    }
}
