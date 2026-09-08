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
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.model.Pos2D;

/**
 * 相位解读三元件测试（v19）：雕文移相器 / 切制裂相器 / 格栅相位加法器。
 *
 * <p>驱动方式：{@code setIncomingEdgeForTest} 注入方波（等效于相邻信号源），
 * 逐 tick 驱动 {@code tickContainerData} 全链路（采样 → 解读 → 注入 → 门控记账）。</p>
 *
 * <p>布局说明（9 列一行）：源位置留空槽，注入写在「元件槽 ← 空槽」的入边上，
 * 与真实游戏一致（蜡-蜡边无人写、恒为死边）——避免测试专用注入在两只互指元件
 * 之间造出游戏中不存在的种子边。</p>
 */
class PhaseInterpretationTest {

    private static final int SIZE = 9;
    private static final int HIGH = 4096;   // |Δ| = √4096 = 64
    private static final int PERIOD = 4;
    private static final int PREF = 4;

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

    private static ItemStack chiseledAimed(Pos2D inputDir) {
        ItemStack s = living(Items.WAXED_CHISELED_COPPER, PREF);
        LivingItemManager.setWaxedChiseledData(s,
            LivingItemManager.getWaxedChiseledData(s).withInputDir(inputDir));
        return s;
    }

    /** 方波值：高电平占前半周期，上升沿落在 t ≡ phase (mod period) */
    private static int square(int tick, int phase, int period) {
        return Math.floorMod(tick - phase, period) < period / 2 ? HIGH : 0;
    }

    private static void inject(ContainerRedstoneData redstone, int slot, int dir, long t, int phase) {
        redstone.setPrevIncomingEdgeForTest(slot, dir, square((int) t - 1, phase, PERIOD));
        redstone.setIncomingEdgeForTest(slot, dir, square((int) t, phase, PERIOD));
    }

    private record Scenario(SimpleContainerContext ctx, ContainerPowerData power) {}

    /** 驱动场景：t ≤ lastInjectTick 时向槽 1 的 LEFT 边注入 φ=0 方波 */
    private static Scenario run(ItemStack[] slots, List<LivingItemFunction.SlotEntry> entries,
            int totalTicks, int lastInjectTick) {
        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        for (long t = 0; t < totalTicks; t++) {
            if (t <= lastInjectTick) {
                inject(redstone, 1, ContainerRedstoneData.EDGE_LEFT, t, 0);
            }
            function.tickContainerData(entries, ctx, new TickContext(ctx));
        }
        return new Scenario(ctx, ctx.getOrCreatePowerData());
    }

    /** 同 run()，但逐 tick 累计发电量（drain 语义），用于冻结验证 */
    private static long runTotal(ItemStack[] slots, List<LivingItemFunction.SlotEntry> entries,
            int totalTicks, int lastInjectTick) {
        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        long total = 0;
        for (long t = 0; t < totalTicks; t++) {
            if (t <= lastInjectTick) {
                inject(redstone, 1, ContainerRedstoneData.EDGE_LEFT, t, 0);
            }
            function.tickContainerData(entries, ctx, new TickContext(ctx));
            for (LivingItemFunction.SlotEntry e : entries) {
                GeneratorState g = ctx.getOrCreatePowerData().getGenerator(e.slotIndex());
                if (g != null) total += g.drainAndEndTick();
            }
        }
        return total;
    }

    // ════════════════ 雕文 = 移相器 ════════════════

    @Test
    @DisplayName("移相器：真实 4t 相位 φ=0 → 派生 φ=1，网络 n=2")
    void shifter_derivesShiftedPhase() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = chiseledAimed(Pos2D.LEFT);                       // 读左边（空槽 0）的真实源
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        Scenario s = run(slots, entries, 20, 19);

        ChannelState ch = s.power.getGenerator(1).channel();
        assertEquals(4, ch.bestPeriod(PREF), "应锁相 4t");
        assertEquals(2, ch.bestN(PREF), "真实 φ=0 + 派生 φ=1 → n=2");

        List<DerivedPhase> reg = s.power.getRegistry(1);
        assertEquals(1, reg.size(), "移相器应登记 1 条驻波");
        assertEquals(1, reg.get(0).offset(), "派生偏移 = 源偏移 + 1");
        assertEquals(PERIOD, reg.get(0).period());
        assertEquals(DerivedPhase.KIND_SHIFT, reg.get(0).kind());
        assertTrue(s.power.getGenerator(1).getEmaPowerRe() > 0, "派生相位应参与发电");
    }

    @Test
    @DisplayName("移相器只感应输入方向：非输入方向的振荡不发电、不派生")
    void shifter_ignoresNonInputDirections() {
        // 雕文在槽 4，inputDir=LEFT；振荡粉贴在 RIGHT 侧（非输入方向）
        ItemStack[] slots = new ItemStack[9];
        slots[3] = living(Items.REDSTONE, 1);   // 输入侧邻居（左侧，无注入）
        slots[4] = chiseledAimed(com.qiqi.li.living.model.Pos2D.LEFT);
        slots[5] = living(Items.REDSTONE, 1);   // RIGHT 侧邻居
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        for (int idx : new int[] {3, 4, 5}) entries.add(new LivingItemFunction.SlotEntry(idx, slots[idx]));

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        // 只往雕文的 RIGHT 边注入 4t 方波（非输入方向）
        for (long t = 0; t < 40; t++) {
            redstone.setPrevIncomingEdgeForTest(4, ContainerRedstoneData.EDGE_RIGHT,
                square((int) t - 1, 0, PERIOD));
            redstone.setIncomingEdgeForTest(4, ContainerRedstoneData.EDGE_RIGHT,
                square((int) t, 0, PERIOD));
            function.tickContainerData(entries, ctx, new TickContext(ctx));
        }

        ContainerPowerData power = ctx.getOrCreatePowerData();
        assertTrue(power.getRegistry(4).isEmpty(), "非输入方向的信号不得派生任何相位");
        assertEquals(0, power.getGenerator(4).channel().bestN(4), "发电采样面应为输入方向单边，n=0");
        assertEquals(0.0, power.getGenerator(4).getEmaPowerRe(), 1e-9, "不应发电");
    }

    @Test
    @DisplayName("移相链【已暂时关闭 2026-09-08】：B 不再读 A 的注册表 → 链断，B 无派生")
    void shifterChain_twoLinks_n3() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = chiseledAimed(Pos2D.LEFT);                       // A：读真实源
        slots[2] = chiseledAimed(Pos2D.LEFT);                       // B：链入口已关（读不到 A 的注册表）
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));
        entries.add(new LivingItemFunction.SlotEntry(2, slots[2]));

        Scenario s = run(slots, entries, 24, 23);

        // 关闭前（链生效）：A 派生 φ=1，B 读 A 的驻波派生 φ=2，全网 n=3——任意信号堆链凑满相，
        // 增益超模（满相只剩材料成本，绕过「真多相靠布局」）。已暂时停用 interpretShifter 的
        // 邻居注册表读取；重新启用时恢复如下断言：
        //   assertEquals(3, ch.bestN(PREF));
        //   assertEquals(2, s.power.getRegistry(2).get(0).offset());
        ChannelState ch = s.power.getGenerator(1).channel();
        assertEquals(2, ch.bestN(PREF), "只剩 A 的真实 φ=0 + A 派生 φ=1 → n=2（链断）");
        assertEquals(1, s.power.getRegistry(1).get(0).offset(), "A 派生 φ=1");
        assertTrue(s.power.getRegistry(2).isEmpty(), "B 读不到 A 的注册表 → 无派生（链断）");
    }

    @Test
    @DisplayName("移相环自熄：两只互指、无任何种子 → 注册表恒空，不发电")
    void shifterCycle_withoutSeed_staysEmpty() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = chiseledAimed(Pos2D.RIGHT);                      // 指向槽 2
        slots[2] = chiseledAimed(Pos2D.LEFT);                       // 指向槽 1
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));
        entries.add(new LivingItemFunction.SlotEntry(2, slots[2]));

        // 完全无注入：环上成员的输入边都是蜡-蜡死边，无种子
        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        for (long t = 0; t < 24; t++) {
            function.tickContainerData(entries, ctx, new TickContext(ctx));
        }
        ContainerPowerData power = ctx.getOrCreatePowerData();

        assertTrue(power.getRegistry(1).isEmpty(), "环上无种子，A 的注册表应恒空");
        assertTrue(power.getRegistry(2).isEmpty(), "环上无种子，B 的注册表应恒空");
        assertEquals(0, power.getGenerator(1).channel().bestN(PREF), "不应有任何相位域");
        assertEquals(0.0, power.getGenerator(1).getEmaPowerRe(), 1e-9, "不应发电");
        assertEquals(0.0, power.getGenerator(2).getEmaPowerRe(), 1e-9, "环上另一台同样不应发电");
    }

    @Test
    @DisplayName("源停跳：解读停止 → 驻波熄灭 → 发电量冻结（死源不永动）")
    void shifter_sourceDies_waveformExtinguishes() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = chiseledAimed(Pos2D.LEFT);
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        Scenario s = run(slots, entries, 150, 15);                  // t=16 起源停跳

        assertTrue(s.power.getRegistry(1).isEmpty(),
            "源停跳远超存活窗口后，驻波应被修剪（updatedTick 停更 → pruneRegistry）");
        // 发电量冻结验证：源停跳 + 存活窗口（32）+ 驻波链式衰减后尾部完全静默
        long total120 = runTotal(slots, entries, 120, 15);
        long total150 = runTotal(slots, entries, 150, 15);
        assertEquals(total120, total150, "t=120 与 t=150 的总产出应一致（尾部静默）");
    }

    // ════════════════ 切制 = 裂相器 ════════════════

    @Test
    @DisplayName("裂相器：一个方波 → 上升沿 φ=0 + 下降沿 φ=2，n=2")
    void splitter_derivesFallingPhase() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = living(Items.WAXED_CUT_COPPER, PREF);
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        Scenario s = run(slots, entries, 24, 23);

        ChannelState ch = s.power.getGenerator(1).channel();
        assertEquals(4, ch.bestPeriod(PREF), "应锁相 4t");
        assertEquals(2, ch.bestN(PREF), "上升沿 φ=0 + 裂相 φ=2 → n=2");

        List<DerivedPhase> reg = s.power.getRegistry(1);
        assertEquals(1, reg.size(), "单条边一方波应登记 1 条裂相驻波");
        assertEquals(2, reg.get(0).offset(), "50% 占空比方波的下降沿在 φ=2");
        assertEquals(DerivedPhase.KIND_SPLIT, reg.get(0).kind());
        assertTrue(s.power.getGenerator(1).getEmaPowerRe() > 0, "裂相相位应参与发电");
    }

    // ════════════════ 格栅 = 相位加法器 ════════════════

    @Test
    @DisplayName("加法器：两路同周期异相（φ=1、φ=2）→ 派生 Σ=3，n=3")
    void adder_sumsTwoPhases() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = living(Items.WAXED_COPPER_GRATE, PREF);
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        for (long t = 0; t < 24; t++) {
            inject(redstone, 1, ContainerRedstoneData.EDGE_LEFT, t, 1);   // φ=1
            inject(redstone, 1, ContainerRedstoneData.EDGE_RIGHT, t, 2);  // φ=2
            function.tickContainerData(entries, ctx, new TickContext(ctx));
        }

        ChannelState ch = ctx.getOrCreatePowerData().getGenerator(1).channel();
        assertEquals(4, ch.bestPeriod(PREF), "应锁相 4t");
        assertEquals(3, ch.bestN(PREF), "真实 φ=1、φ=2 + 派生 Σ=3 → n=3");

        List<DerivedPhase> reg = ctx.getOrCreatePowerData().getRegistry(1);
        assertEquals(1, reg.size(), "加法器应登记 1 条和驻波");
        assertEquals(3, reg.get(0).offset(), "Σφ = 1 + 2 = 3");
        assertEquals(DerivedPhase.KIND_ADD, reg.get(0).kind());
    }

    @Test
    @DisplayName("加法器去重诚实：和撞上已有相位（φ=0、φ=2 → Σ=2）→ n 不虚增")
    void adder_duplicateSum_noFalseGain() {
        ItemStack[] slots = new ItemStack[SIZE];
        slots[1] = living(Items.WAXED_COPPER_GRATE, PREF);
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();
        for (long t = 0; t < 24; t++) {
            inject(redstone, 1, ContainerRedstoneData.EDGE_LEFT, t, 0);   // φ=0
            inject(redstone, 1, ContainerRedstoneData.EDGE_RIGHT, t, 2);  // φ=2
            function.tickContainerData(entries, ctx, new TickContext(ctx));
        }

        ChannelState ch = ctx.getOrCreatePowerData().getGenerator(1).channel();
        assertEquals(2, ch.bestN(PREF), "Σ=2 与真实 φ=2 同偏移 → 去重，n 停在 2");
        assertEquals(2, ctx.getOrCreatePowerData().getRegistry(1).get(0).offset(),
            "派生条目仍登记（Σ=2），但域内去重不虚增 n");
    }
}
