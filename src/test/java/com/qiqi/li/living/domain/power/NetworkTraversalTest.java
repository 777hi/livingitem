package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.domain.redstone.LivingLeverFunction;
import com.qiqi.li.living.domain.redstone.LivingRedstoneBlockFunction;
import com.qiqi.li.living.domain.redstone.RedstoneSnapshotProvider;
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import com.qiqi.li.living.domain.power.PhaseEvent;

/**
 * 电力层「按网络组件遍历」重构回归测试（v3 重构：逐发电机 BFS → 按组件 BFS + copyFrom）。
 *
 * <p>背景：原 {@link LivingWaxedCopperFunction#tickContainerData} 对每台发电机各跑一次
 * {@code runBfs}，同氧化级连通块内 G 台发电机遍历同一张边集 → G 倍冗余。重构后按
 * (TopoKey, rep, channelIdx) 组件遍历，每组件仅锚点跑一次 BFS，其余发电机
 * {@code ChannelState.copyFrom} 锚点的通道（相位历史随之同步，O(域) 极廉价），
 * accountEnergy 仍逐机调用（各自 pref 读共享/复制后的通道）。</p>
 *
 * <p>本测试锁三件事：
 * <ol>
 *   <li>{@code ChannelState.copyFrom} 深拷贝正确性（直接单测，证明共享历史逐字节一致且独立）；</li>
 *   <li>同连通块多台发电机共享同一相位历史 —— 各机 ChannelState 的 n/period/factor 完全一致；</li>
 *   <li>总发电量 = 单机 × 组件内发电机数（共享边未被 G 倍重复计入，逐机 accountEnergy 才累加）。</li>
 * </ol>
 *
 * <p>注：重构的「性能」收益（每组件仅一次 BFS）是结构性可见的——{@code tickContainerData}
 * 的 groups 循环对每个 ComponentId 仅锚点执行 runBfs——本测试在输出层面校验行为等价性
 * （共享历史 + 深拷贝 + 可加能量），与读码互证。</p>
 */
class NetworkTraversalTest {

    private final LivingWaxedCopperFunction function = new LivingWaxedCopperFunction();

    private static final int SIZE = 9;
    private static final int WIDTH = 9;
    private static final int PREF = 4;          // 偏好周期 = 堆叠数
    private static final int PERIOD = 4;        // 振荡器周期
    private static final int HIGH = 4096;       // 边信号幅度（|Δ| = √4096 = 64）

    /** 测试用物品访问器——只暴露 IItemHandler 的模组容器（不实现 Container） */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(ItemStack... slots) {
            this.slots = slots;
        }

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

    /** 运行一个场景：numGens 台发电机相邻摆放（同一连通块），注入周期振荡器边信号，驱动 ticks 个 tick。 */
    private static final class Scenario {
        final SimpleContainerContext ctx;
        final ContainerPowerData power;

        Scenario(SimpleContainerContext ctx, ContainerPowerData power) {
            this.ctx = ctx;
            this.power = power;
        }
    }

    private Scenario runScenario(int numGens, int ticks) {
        ItemStack[] slots = new ItemStack[SIZE];
        for (int i = 0; i < numGens; i++) {
            slots[i] = living(Items.WAXED_COPPER_BLOCK, PREF);
        }
        IItemHandler handler = new FakeHandler(slots);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();

        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        for (int i = 0; i < numGens; i++) {
            entries.add(new LivingItemFunction.SlotEntry(i, slots[i]));
        }

        // 注入边所在槽：BFS 会从锚点(槽 0)出发访问到该槽并检测到上升沿。
        // 入边语义（v15 每槽自有出边）：写入「邻居朝该槽发出的出边」——
        // 注入 slot 沿 RIGHT 的邻居的 LEFT 出边（9×1 单行网格，UP/DOWN 无邻居）。
        // 单机 → 槽 0（邻居=槽 1）；双机 → 槽 1（邻居=槽 2，空槽亦可承载出边）。
        int edgeSlot = numGens == 1 ? 0 : 1;
        int edgeDir = ContainerRedstoneData.EDGE_RIGHT;

        for (int t = 0; t <= ticks; t++) {
            int v = (Math.floorMod(t, PERIOD) < PERIOD / 2) ? HIGH : 0;
            int pv = (Math.floorMod(t - 1, PERIOD) < PERIOD / 2) ? HIGH : 0;
            // 首拍热身（2026-09-09 根修对齐）：红石账本首拍 hasEdgeHistory=false，
            // 边检测整段跳过（真实游戏里容器已跑多 tick 才有振荡，首拍无沿）。
            // 真实游戏里容器已跑多 tick 才有振荡；测试从 t=0 注入，须先空跑一拍建立边历史。
            if (t == 0) {
                function.tickContainerData(entries, ctx, new TickContext(ctx));
            }
            redstone.setPrevIncomingEdgeForTest(edgeSlot, edgeDir, pv);
            redstone.setIncomingEdgeForTest(edgeSlot, edgeDir, v);

            TickContext tick = new TickContext(ctx);
            function.tickContainerData(entries, ctx, tick);
        }

        return new Scenario(ctx, ctx.getOrCreatePowerData());
    }

    // ════════════════════════════════════════════════════════════════
    // 1) copyFrom 深拷贝正确性（直接单测）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ChannelState.copyFrom：深拷贝域状态且与源独立")
    void copyFrom_deepCopiesDomainsAndStaysIndependent() {
        GeneratorState src = new GeneratorState();
        src.setPreferredPeriodFromStack(PREF);
        ChannelState ch = src.channel();
        // 填充一个 4t 域（单路），|Δ| = 4096 → eff_δ_sum = 64
        for (int t = 0; t <= 16; t += 4) {
            ch.onPhaseEvent(new PhaseEvent(0, 4, t % 4, HIGH, t), src.preferredPeriod());
        }

        GeneratorState dst = new GeneratorState();
        dst.setPreferredPeriodFromStack(PREF);
        ChannelState copy = dst.channel();
        copy.copyFrom(ch);

        // 深拷贝值一致
        assertEquals(ch.bestN(PREF), copy.bestN(PREF), "n 应一致");
        assertEquals(ch.bestPeriod(PREF), copy.bestPeriod(PREF), "周期应一致");
        assertEquals(ch.bestEffDeltaSum(PREF), copy.bestEffDeltaSum(PREF), 1e-9, "eff_δ_sum 应一致");
        assertEquals(ch.bestFactor(PREF), copy.bestFactor(PREF), 1e-6, "合因子应一致");

        // 深拷贝独立性：之后修改源，副本不受影响（证明不是引用共享）
        ch.onPhaseEvent(new PhaseEvent(1, 3, 0, 100, 100), src.preferredPeriod());
        assertEquals(4, copy.bestPeriod(PREF), "副本的 4t 域不应受源后续事件影响");
        assertEquals(1, copy.bestN(PREF), "副本相数应仍为 1");
    }

    // ════════════════════════════════════════════════════════════════
    // 2) + 3) 同连通块多机：共享相位历史 + 可加能量
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同连通块 2 台发电机：共享相位历史（n/period/factor 一致）+ 总发电量 = 单机 ×2")
    void sameComponent_twoGenerators_shareHistoryAndAddEnergy() {
        int ticks = 40;
        Scenario one = runScenario(1, ticks);
        Scenario two = runScenario(2, ticks);

        GeneratorState g1 = one.power.getGenerator(0);
        GeneratorState a = two.power.getGenerator(0);
        GeneratorState b = two.power.getGenerator(1);

        // 前置：振荡器确实锁相到 4t 单域（否则下列相等断言会平凡成立，失去意义）
        assertEquals(4, g1.channel().bestPeriod(PREF), "单机基线应锁相 4t");
        assertEquals(1, g1.channel().bestN(PREF), "单机基线 n=1");
        assertEquals(4, a.channel().bestPeriod(PREF), "双机 A 应锁相 4t");
        assertEquals(1, a.channel().bestN(PREF), "双机 A n=1");

        // (2) 两台发电机通道状态完全一致（共享锚点 BFS + copyFrom 深拷贝正确）
        assertEquals(a.channel().bestN(PREF), b.channel().bestN(PREF), "两台发电机相数 n 应相等");
        assertEquals(a.channel().bestPeriod(PREF), b.channel().bestPeriod(PREF), "最佳周期应相等");
        assertEquals(a.channel().bestEffDeltaSum(PREF), b.channel().bestEffDeltaSum(PREF), 1e-9, "eff_δ_sum 应相等");
        assertEquals(a.channel().bestFactor(PREF), b.channel().bestFactor(PREF), 1e-6, "合因子应相等");

        // (2') DomainSnapshot 的 n/period 相等（用户显式要求）
        var ta = LivingWaxedCopperFunction.buildTelemetry(a, PREF,
            LivingWaxedGeneratorData.FORM_BLOCK, 0, two.power);
        var tb = LivingWaxedCopperFunction.buildTelemetry(b, PREF,
            LivingWaxedGeneratorData.FORM_BLOCK, 0, two.power);
        assertEquals(ta.domains().size(), tb.domains().size(), "域数量应相等");
        for (int i = 0; i < ta.domains().size(); i++) {
            assertEquals(ta.domains().get(i).period(), tb.domains().get(i).period(), "域周期应相等");
            assertEquals(ta.domains().get(i).n(), tb.domains().get(i).n(), "域相数应相等");
        }

        // (2'') 两机 EMA 功率完全相同（同一信号）
        assertTrue(a.getEmaPowerRe() > 0, "发电机 A 应有发电量");
        assertEquals(a.getEmaPowerRe(), b.getEmaPowerRe(), 1e-9, "两机 EMA 功率应相同");

        // (3) 总发电量 = 单机 × 组件内发电机数（共享边未被 G 倍重复计入）
        // v18 容器总账已拆，改用锈级 EMA 验证：EMA 线性，2 机每 tick 输入 = 单机 ×2。
        double singleEmaRe = one.power.getLevelEmaPowerRe(0);
        double twoEmaRe = two.power.getLevelEmaPowerRe(0);
        assertTrue(singleEmaRe > 0, "单机应有发电量");
        assertEquals(2.0 * singleEmaRe, twoEmaRe, 1e-6,
            "同连通块 2 机锈级总发电量应 = 单机 ×2（共享边不重复计 G 倍）");
    }

    // ════════════════════════════════════════════════════════════════
    // 4) 不同氧化等级 = 不同网络：互不共享（对照）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("相邻但不同氧化等级：隔离为两个网络，互不影响")
    void differentOxidationLevels_areIsolatedNetworks() {
        // 槽 0 新鲜铜(氧化 0)、槽 1 氧化铜(氧化 3)，相邻但不互通
        ItemStack[] slots = new ItemStack[SIZE];
        slots[0] = living(Items.WAXED_COPPER_BLOCK, PREF);          // 氧化 0
        slots[1] = living(Items.WAXED_OXIDIZED_COPPER, PREF);       // 氧化 3

        IItemHandler handler = new FakeHandler(slots);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();

        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(0, slots[0]));
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        // 只在槽 0（新鲜铜）注入上升沿：氧化铜(槽 1)应完全收不到
        // 入边语义：写槽 0 的右邻居(槽 1)朝槽 0 的 LEFT 出边（9×1 网格无 UP/DOWN 邻居）
        int edgeDir = ContainerRedstoneData.EDGE_RIGHT;
        for (int t = 0; t <= 40; t++) {
            if (t == 0) {   // 首拍热身（根修对齐：首拍无沿，需先建立边历史）
                function.tickContainerData(entries, ctx, new TickContext(ctx));
            }
            int v = (Math.floorMod(t, PERIOD) < PERIOD / 2) ? HIGH : 0;
            int pv = (Math.floorMod(t - 1, PERIOD) < PERIOD / 2) ? HIGH : 0;
            redstone.setPrevIncomingEdgeForTest(0, edgeDir, pv);
            redstone.setIncomingEdgeForTest(0, edgeDir, v);

            TickContext tick = new TickContext(ctx);
            function.tickContainerData(entries, ctx, tick);
        }

        ContainerPowerData power = ctx.getOrCreatePowerData();
        GeneratorState fresh = power.getGenerator(0);
        GeneratorState oxidized = power.getGenerator(1);

        assertEquals(4, fresh.channel().bestPeriod(PREF), "新鲜铜应锁相 4t");
        assertEquals(1, fresh.channel().bestN(PREF), "新鲜铜 n=1");
        assertEquals(0, oxidized.channel().bestN(PREF), "氧化铜(不同网络)不应收到任何相位事件");
        assertTrue(fresh.getEmaPowerRe() > 0, "新鲜铜应发电");
        assertEquals(0.0, oxidized.getEmaPowerRe(), 1e-9, "氧化铜不应发电");
    }

    // ════════════════════════════════════════════════════════════════
    // 5) 量化遥测降低稳态脏写频率（服务器场景优化）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多锈级稳态：遥测量化后不再每 tick 脏写发电机槽位")
    void quantizedTelemetry_doesNotDirtyInSteadyState() {
        // 两个锈级：氧化 0（pref=4）与氧化 3（pref=8），基础功率不同 → 平衡度 s<1，
        // 共振增益 / 平衡度 EMA 渐近收敛（若未量化会每 tick 微动 → 每 tick 脏写）。
        ItemStack[] slots = new ItemStack[SIZE];
        slots[0] = living(Items.WAXED_COPPER_BLOCK, 4);        // 锈级 0
        slots[1] = living(Items.WAXED_OXIDIZED_COPPER, 8);     // 锈级 3
        IItemHandler handler = new FakeHandler(slots);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();

        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(0, slots[0]));
        entries.add(new LivingItemFunction.SlotEntry(1, slots[1]));

        int dir = ContainerRedstoneData.EDGE_RIGHT;
        TickContext lastTick = null;
        // 跑足够多 tick 让共振 EMA 完全收敛（EMA_ALPHA=0.125，~40 tick 到 99%；跑 100 保险）
        for (int t = 0; t <= 100; t++) {
            if (t == 0) {   // 首拍热身（根修对齐：首拍无沿，需先建立边历史）
                function.tickContainerData(entries, ctx, new TickContext(ctx));
            }
            int v = (Math.floorMod(t, PERIOD) < PERIOD / 2) ? HIGH : 0;
            int pv = (Math.floorMod(t - 1, PERIOD) < PERIOD / 2) ? HIGH : 0;
            redstone.setPrevIncomingEdgeForTest(0, dir, pv);
            redstone.setIncomingEdgeForTest(0, dir, v);
            // 槽 1 的右邻居（槽 2）朝槽 1 的 LEFT 出边
            redstone.setPrevIncomingEdgeForTest(1, dir, pv);
            redstone.setIncomingEdgeForTest(1, dir, v);

            TickContext tick = new TickContext(ctx);
            function.tickContainerData(entries, ctx, tick);
            lastTick = tick;
        }

        // 前置：场景确实在多锈级共振稳态（否则「不脏写」会平凡成立，失去意义）
        // 遥测数据已写入运行时缓存，不再写入 DataComponent
        var gd = ContainerRuntimeCache.get(ctx.getContainerKey(), 0);
        assertTrue(gd.isGenerator(), "应有发电机遥测数据（运行时缓存）");
        assertEquals(2, gd.generatorTelemetry().activeLevels(), "应有两个活跃锈级（共振生效）");
        assertTrue(gd.generatorTelemetry().emaPowerMilliFe() > 0, "应有发电量");
        assertTrue(gd.generatorTelemetry().resonanceGain() > 1.0, "共振增益应 > 1（多锈级共振）");

        // 收敛后稳态：遥测被量化钉死 → 发电机槽位不再每 tick 标脏
        assertFalse(lastTick.dirtySlots.contains(0),
            "稳态下锈级 0 槽位不应每 tick 脏写：dirtySlots=" + lastTick.dirtySlots);
        assertFalse(lastTick.dirtySlots.contains(1),
            "稳态下锈级 3 槽位不应每 tick 脏写：dirtySlots=" + lastTick.dirtySlots);
    }

    // ════════════════════════════════════════════════════════════════
    // 6) 端到端回归：真实传播 calculate → 电力采样（v15 边模型回归守卫）
    // ════════════════════════════════════════════════════════════════
    //
    // 背景：v15 把 EdgeGrid 从共享边改为「每槽自有出边」后，涂蜡槽（绝缘，信号层
    // 从不为其写边）读自己的出边永远为 0，电力层彻底失去信号——而现有测试全部经
    // setIncomingEdgeForTest 直接注入边，恰好绕过真实传播，回归未被抓住。本测试
    // 不注入任何边，走「红石 calculate 真实传播 → 电力层逐方向采样」全链路。
    //
    // 场景：拉杆振荡器（4t 方波：ON 2 tick → OFF 2 tick，上升沿间隔 4t）与
    // 涂蜡铜块发电机相邻，中间无红石粉——拉杆作为信号源直接写自己的出边
    // （朝涂蜡槽方向），电力层采样涂蜡槽的入边应能检测到 4t 周期上升沿并发电。
    // 稳态跳过路径同样被覆盖：拉杆 OFF/ON 持续期间 calculate 会进入跳过分支，
    // 验证 prevEdgeGrid 同步修复（否则跳过 tick 重复产生假上升沿压碎周期估计）。

    @Test
    @DisplayName("E2E：拉杆振荡器 → 真实传播 → 涂蜡发电机锁相 4t 并发电")
    void endToEnd_realPropagation_powersGenerator() {
        // 网格 9×1：槽 0 拉杆（振荡器），槽 1 涂蜡铜块发电机（pref=4）
        ItemStack[] slots = new ItemStack[SIZE];
        ItemStack lever = living(Items.LEVER, 1);
        ItemStack genStack = living(Items.WAXED_COPPER_BLOCK, 4);
        slots[0] = lever;
        slots[1] = genStack;
        IItemHandler handler = new FakeHandler(slots);
        SimpleContainerContext ctx = new SimpleContainerContext(handler, new ArrayList<>(), new ArrayList<>());
        ContainerRedstoneData redstone = ctx.getOrCreateRedstoneData();

        // 电力层 entries（发电机）
        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(1, genStack));

        // 红石功能槽位（拉杆；涂蜡铜块是绝缘体不参与信号层，无需注册）
        var leverSlots = java.util.Set.of(0);
        // 快照贡献者按类去重，重复注册安全
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        int TOTAL = 48;   // 4t 周期 × 12 个完整周期
        for (int t = 0; t < TOTAL; t++) {
            // 振荡器：4t 方波（ON 2t → OFF 2t），上升沿间隔 4t
            boolean powered = (Math.floorMod(t, 4) < 2);
            LivingItemManager.setLeverData(lever, new com.qiqi.li.living.domain.redstone.LivingLeverData(powered));
            // 生产契约：DataComponent 就地变更必须 bump 修订计数，否则稳态跳过判定失明
            ctx.syncSlotToClients(0, lever);

            // ① 信号层：真实传播（calculate 全链路：源写边 + 双缓冲 + 稳态跳过判定）
            TickContext tick = new TickContext(ctx);
            tick.setFunctionSlots(java.util.Map.of(LivingLeverFunction.ID, leverSlots));
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, tick);

            // ② 电力层：采样涂蜡槽入边（真实 edgeGrid，无任何测试注入）
            function.tickContainerData(entries, ctx, tick);
        }

        ContainerPowerData power = ctx.getOrCreatePowerData();
        GeneratorState gen = power.getGenerator(1);
        assertTrue(gen != null, "应有发电机状态");
        assertEquals(4, gen.channel().bestPeriod(4), "发电机应锁相 4t（拉杆方波上升沿间隔）");
        assertEquals(1, gen.channel().bestN(4), "单路信号 n=1");
        assertTrue(gen.getEmaPowerRe() > 0,
            "端到端发电量应 > 0（真实传播 + 电力采样全链路），实际=" + gen.getEmaPowerRe());
    }
}