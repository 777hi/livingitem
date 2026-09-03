package com.qiqi.li.living.domain.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * {@link ContainerRedstoneData} 信号传播测试。
 *
 * <p>红石传播是全项目最复杂的纯逻辑（多阶段状态机 + 网格 BFS + 双缓冲），
 * 也是回归代价最高的部分。此测试直接驱动 {@code calculate()}，
 * 不依赖 Level 或真实容器。</p>
 */
class ContainerRedstoneDataTest {

    private static final int WIDTH = 9;
    private static final int SIZE = 27;

    /** 构造一个活物品（已打上 IS_LIVING 标记） */
    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /**
     * 驱动一次传播。
     *
     * <p>模拟 {@code ContainerLivingItemHandler.processContext} 的行为：
     * 填充 functionSlots 后调用 calculate。</p>
     */
    private static ContainerRedstoneData propagate(
            FakeContainerContext ctx, Map<String, Set<Integer>> functionSlots) {
        ContainerRedstoneData data = new ContainerRedstoneData();
        tickOnce(data, ctx, functionSlots);
        return data;
    }

    private static void tickOnce(ContainerRedstoneData data,
            FakeContainerContext ctx, Map<String, Set<Integer>> functionSlots) {
        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(functionSlots);
        data.resetProcessedFlag();
        data.calculate(ctx, tick);
    }

    // ════════════════════════════════════════
    // 信号上限规则
    // ════════════════════════════════════════

    @ParameterizedTest(name = "{0} 个红石 → 信号上限 {1}")
    @CsvSource({
        "1, 15",
        "2, 4",
        "3, 9",
        "4, 16",
        "8, 64",
        "16, 256"
    })
    @DisplayName("信号上限：1 个为 15，其余为堆叠数的平方")
    void getSignalCap_followsSquareRuleExceptSingle(int stackCount, int expectedCap) {
        assertEquals(expectedCap, ContainerRedstoneData.getSignalCap(stackCount));
    }

    // ════════════════════════════════════════
    // 空容器与无源场景
    // ════════════════════════════════════════

    @Test
    @DisplayName("空容器：所有槽位信号为 0")
    void emptyContainer_hasNoSignal() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        var data = propagate(ctx, Map.of());

        for (int i = 0; i < SIZE; i++) {
            assertEquals(0, data.getSignal(i), "槽位 " + i + " 应无信号");
        }
    }

    @Test
    @DisplayName("只有红石粉无信号源：红石粉保持无信号")
    void dustWithoutSource_staysUnpowered() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(10, living(Items.REDSTONE, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var data = propagate(ctx, Map.of(LivingRedstoneFunction.ID, Set.of(10, 11)));

        assertEquals(0, data.getSignal(10));
        assertEquals(0, data.getSignal(11));
    }

    // ════════════════════════════════════════
    // 红石块作为恒定信号源
    // ════════════════════════════════════════

    @Test
    @DisplayName("红石块：为相邻红石粉提供信号")
    void redstoneBlock_poweresAdjacentDust() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var data = propagate(ctx, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11)));

        assertTrue(data.getSignal(11) > 0,
            "红石块右侧的红石粉应被供电，实际=" + data.getSignal(11));
    }

    @Test
    @DisplayName("红石块：信号沿红石粉链衰减")
    void redstoneBlock_signalDecaysAlongDustChain() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        // 第 2 行（槽位 9..17）：红石块 + 连续红石粉
        ctx.set(9, living(Items.REDSTONE_BLOCK, 1));
        for (int slot = 10; slot <= 14; slot++) {
            ctx.set(slot, living(Items.REDSTONE, 1));
        }

        var data = propagate(ctx, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRedstoneFunction.ID, Set.of(10, 11, 12, 13, 14)));

        int near = data.getSignal(10);
        int far = data.getSignal(14);

        assertTrue(near > 0, "紧邻红石块的槽位应有信号");
        assertTrue(far < near,
            "远端信号应弱于近端：near=" + near + ", far=" + far);
    }

    // ════════════════════════════════════════
    // 拉杆：持续型信号源
    // ════════════════════════════════════════

    @Test
    @DisplayName("拉杆关闭时不供电")
    void lever_doesNotPowerWhenOff() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(10, living(Items.LEVER, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var data = propagate(ctx, Map.of(
            LivingLeverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11)));

        assertEquals(0, data.getSignal(11), "拉杆默认关闭，不应供电");
    }

    @Test
    @DisplayName("拉杆打开时为相邻红石粉供电")
    void lever_poweresAdjacentDustWhenOn() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ItemStack lever = living(Items.LEVER, 1);
        LivingItemManager.setLeverData(lever, new LivingLeverData(true));
        ctx.set(10, lever);
        ctx.set(11, living(Items.REDSTONE, 1));

        var data = propagate(ctx, Map.of(
            LivingLeverFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11)));

        assertTrue(data.getSignal(11) > 0,
            "拉杆打开后应为相邻红石粉供电，实际=" + data.getSignal(11));
    }

    // ════════════════════════════════════════
    // 红石灯：信号可视化
    // ════════════════════════════════════════

    @Test
    @DisplayName("红石灯：被供电时点亮，失去信号后熄灭")
    void lamp_litFollowsSignal() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ItemStack lamp = living(Items.REDSTONE_LAMP, 1);
        ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(11, lamp);

        var slots = Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneLampFunction.ID, Set.of(11));

        propagate(ctx, slots);
        assertTrue(LivingItemManager.getLampData(lamp).lit(),
            "紧邻红石块的灯应点亮");
    }

    // ════════════════════════════════════════
    // 堆叠数影响
    // ════════════════════════════════════════

    @Test
    @DisplayName("堆叠数越大，红石粉可传播的距离越远")
    void higherStackCount_propagatesFarther() {
        // 单个红石粉：上限 15
        var ctxSingle = new FakeContainerContext(SIZE, WIDTH);
        ctxSingle.set(9, living(Items.REDSTONE_BLOCK, 1));
        for (int slot = 10; slot <= 17; slot++) {
            ctxSingle.set(slot, living(Items.REDSTONE, 1));
        }
        var single = propagate(ctxSingle, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRedstoneFunction.ID, Set.of(10, 11, 12, 13, 14, 15, 16, 17)));

        // 4 个红石粉：上限 16
        var ctxStacked = new FakeContainerContext(SIZE, WIDTH);
        ctxStacked.set(9, living(Items.REDSTONE_BLOCK, 1));
        for (int slot = 10; slot <= 17; slot++) {
            ctxStacked.set(slot, living(Items.REDSTONE, 4));
        }
        var stacked = propagate(ctxStacked, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRedstoneFunction.ID, Set.of(10, 11, 12, 13, 14, 15, 16, 17)));

        assertTrue(stacked.getSignal(17) >= single.getSignal(17),
            "堆叠数更高时远端信号不应更弱：stacked=" + stacked.getSignal(17)
                + ", single=" + single.getSignal(17));
    }

    // ════════════════════════════════════════
    // 归零行为（对应上一轮修复的 grouped.isEmpty() 分支）
    // ════════════════════════════════════════

    @Test
    @DisplayName("信号源被移除后，残留信号归零")
    void removingSource_clearsResidualSignal() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11)));
        assertTrue(data.getSignal(11) > 0, "前置条件：红石粉应先被供电");

        // 取走所有活物品，模拟容器被清空后的下一 tick
        ctx.set(10, ItemStack.EMPTY);
        ctx.set(11, ItemStack.EMPTY);
        tickOnce(data, ctx, Map.of());

        assertEquals(0, data.getSignal(11),
            "信号源移除后残留信号必须归零");
    }

    @Test
    @DisplayName("同一 tick 内重复调用 calculate 只生效一次")
    void calculate_isIdempotentWithinSameTick() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        TickContext tick = new TickContext(ctx);
        tick.setFunctionSlots(slots);

        data.calculate(ctx, tick);
        int first = data.getSignal(11);
        // 不重置 processedThisTick，第二次应直接返回
        data.calculate(ctx, tick);

        assertEquals(first, data.getSignal(11),
            "同 tick 内重复 calculate 不应改变结果");
    }

    // ════════════════════════════════════════
    // 传播节拍（每 game tick 一次）
    // ════════════════════════════════════════

    /**
     * 传播不跳帧：任意 game tick 都执行完整传播。
     *
     * <p>早期实现每 2 tick 才传播一次（先是容器私有 tickCounter，后改为
     * {@code getGameTime() % 2}），这使容器内的时间分辨率被限制在 2 tick，
     * 最快振荡周期只能到 4 tick。实测传播开销在满载 54 格容器下约 9μs，
     * 不足单 tick 预算的 0.02%，因此取消跳帧换取 1 tick 分辨率。</p>
     */
    @Test
    @DisplayName("任意 game tick 都执行传播，不跳帧")
    void propagation_runsOnEveryGameTick() {
        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        for (long gameTime : new long[]{100, 101}) {
            var ctx = new FakeContainerContext(SIZE, WIDTH).withGameTime(gameTime);
            ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
            ctx.set(11, living(Items.REDSTONE, 1));
            var data = new ContainerRedstoneData();
            tickOnce(data, ctx, slots);
            assertTrue(data.getSignal(11) > 0,
                "game tick " + gameTime + " 应正常传播");
        }
    }

    /**
     * 中继器延迟以 game tick 计数，档位 N = 2N game tick。
     *
     * <p>保持原版「1 红石刻 = 2 game tick」语义：一档中继器延迟 2 tick，
     * 因此充能后需再经过 2 次传播才输出。</p>
     */
    @Test
    @DisplayName("一档中继器延迟 2 game tick 后才输出")
    void repeater_delayCountsInGameTicks() {
        var ctx = new FakeContainerContext(SIZE, WIDTH).withGameTime(100);
        ctx.set(9, living(Items.REDSTONE_BLOCK, 1));
        ItemStack repeater = living(Items.REPEATER, 1);
        LivingItemManager.setRepeaterData(repeater,
            LivingRepeaterData.DEFAULT.withDirection(Pos2D.RIGHT));
        ctx.set(10, repeater);
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRepeaterFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();

        // 第 1 次传播：检测到输入，充能，delayTimer = 1 档 × 2 = 2
        tickOnce(data, ctx, slots);
        assertEquals(2, LivingItemManager.getRepeaterData(ctx.getItem(10)).delayTimer(),
            "一档中继器充能后 delayTimer 应为 2 game tick");

        // 第 2、3 次传播递减计时器，第 3 次归零后中继器开始输出
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertEquals(0, LivingItemManager.getRepeaterData(ctx.getItem(10)).delayTimer(),
            "经过 2 game tick 后计时器应归零");

        tickOnce(data, ctx, slots);
        assertTrue(data.getSignal(11) > 0, "延迟结束后中继器应向前方输出信号");
    }

    // ════════════════════════════════════════
    // 稳态跳过（steady-state skip）
    // ════════════════════════════════════════

    @Test
    @DisplayName("稳态跳过：物品与外部输入不变、无倒计时时 calculate 被跳过且信号不变")
    void steadyState_skipsWhenUnchanged() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(4, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(5, living(Items.REDSTONE, 1));
        ctx.set(6, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(4),
            LivingRedstoneFunction.ID, Set.of(5, 6));

        var data = new ContainerRedstoneData();

        tickOnce(data, ctx, slots);
        int sig5First = data.getSignal(5);
        int sig6First = data.getSignal(6);
        assertTrue(sig5First > 0 && sig6First > 0, "前置：红石块应点亮粉链");

        // 第 2、3 次：物品与外部输入都不变 → 应跳过（复用 edgeGrid）
        tickOnce(data, ctx, slots);
        tickOnce(data, ctx, slots);
        assertEquals(2, data.steadySkipCount, "物品不变时应跳过 2 次");
        assertEquals(sig5First, data.getSignal(5), "跳过不应改变信号");
        assertEquals(sig6First, data.getSignal(6), "跳过不应改变信号");

        // 第 4 次：移除粉链末端（物品变更 → rev 变化）→ 强制重算，跳过计数不再增长
        ctx.set(6, ItemStack.EMPTY);
        tickOnce(data, ctx, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(4),
            LivingRedstoneFunction.ID, Set.of(5)));
        assertEquals(2, data.steadySkipCount, "物品变化后应强制重算，跳过计数不变");
        assertEquals(0, data.getSignal(6), "移除粉链末端后该格信号应归零");
        assertEquals(sig5First, data.getSignal(5), "上游粉仍应被点亮");
    }

    @Test
    @DisplayName("稳态跳过不冻结时序：中继器倒计时期间仍逐 tick 重算，归零后才跳过")
    void steadyState_noSkipWhileRepeaterCountingDown() {
        var ctx = new FakeContainerContext(SIZE, WIDTH).withGameTime(100);
        ctx.set(9, living(Items.REDSTONE_BLOCK, 1));
        ItemStack repeater = living(Items.REPEATER, 1);
        LivingItemManager.setRepeaterData(repeater, LivingRepeaterData.DEFAULT.withDirection(Pos2D.RIGHT));
        ctx.set(10, repeater);
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRepeaterFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();

        // 充能与倒计时期间（delayTimer 仍在递减）必须全算，绝不能跳过
        tickOnce(data, ctx, slots);
        assertEquals(0, data.steadySkipCount, "首次必全算");
        tickOnce(data, ctx, slots);
        assertEquals(0, data.steadySkipCount, "倒计时期间不应跳过");
        tickOnce(data, ctx, slots);
        assertEquals(0, data.steadySkipCount, "倒计时期间不应跳过");
        assertEquals(0, LivingItemManager.getRepeaterData(ctx.getItem(10)).delayTimer(), "计时器应已归零");

        // 归零且输入稳定后进入稳态，下一次才允许跳过，且输出保持
        tickOnce(data, ctx, slots);
        assertEquals(1, data.steadySkipCount, "倒计时结束后进入稳态才跳过");
        assertTrue(data.getSignal(11) > 0, "跳过不应丢失中继器输出");
    }

    // ════════════════════════════════════════
    // 容器尺寸适配
    // ════════════════════════════════════════

    @Test
    @DisplayName("容器宽度变化时网格重建，不越界")
    void changingContainerWidth_rebuildsGridSafely() {
        var data = new ContainerRedstoneData();

        var wide = new FakeContainerContext(27, 9);
        wide.set(0, living(Items.REDSTONE_BLOCK, 1));
        tickOnce(data, wide, Map.of(LivingRedstoneBlockFunction.ID, Set.of(0)));

        // 同一个 data 实例换到 5 格漏斗布局
        var narrow = new FakeContainerContext(5, 5);
        narrow.set(0, living(Items.REDSTONE_BLOCK, 1));
        tickOnce(data, narrow, Map.of(LivingRedstoneBlockFunction.ID, Set.of(0)));

        // 只要不抛异常即可，同时验证越界槽位返回 0
        assertEquals(0, data.getSignal(26), "越界槽位应返回 0 而非抛异常");
    }

    @Test
    @DisplayName("非 9 列容器（漏斗 5 格单行）传播正常")
    void hopperLayout_propagatesInSingleRow() {
        var ctx = new FakeContainerContext(5, 5);
        ctx.set(0, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(1, living(Items.REDSTONE, 1));
        ctx.set(2, living(Items.REDSTONE, 1));

        var data = propagate(ctx, Map.of(
            LivingRedstoneBlockFunction.ID, Set.of(0),
            LivingRedstoneFunction.ID, Set.of(1, 2)));

        assertTrue(data.getSignal(1) > 0, "单行布局中相邻槽位应被供电");
    }

    // ════════════════════════════════════════
    // 比较器输出传播（对应本轮修复的「只能传一格」bug）
    // ════════════════════════════════════════

    /**
     * 修复回归：比较器后方经红石粉链输入时，输出必须沿粉链传播超过一格。
     *
     * <p>布局（9 列第 2 行，槽位 9..17）：
     * 红石块(9) → 粉(10) → 粉(11) → 比较器(12,朝右) → 粉(13) → 粉(14)</p>
     *
     * <p>根因：phase1 把比较器当源收集时，其输出依赖后方输入（粉 11），
     * 而粉链要在 phase2 才传播，故 phase1 误判输出为 0 并跳过入队；
     * phase3/phase4 重算输出却未把下游粉链并入传播（{@code powerConductiveNeighbor}
     * 早退掩码含 BIT_DUST/BIT_COPPER），导致输出只亮紧邻一格。</p>
     */
    @Test
    @DisplayName("比较器后方经粉链输入：输出传播超过一格")
    void comparator_rearFedByDust_propagatesPastOneBlock() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(9, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(10, living(Items.REDSTONE, 1));
        ctx.set(11, living(Items.REDSTONE, 1));
        ItemStack comparator = living(Items.COMPARATOR, 1);
        LivingItemManager.setComparatorData(comparator,
            LivingComparatorData.DEFAULT.withDirection(Pos2D.RIGHT));
        ctx.set(12, comparator);
        ctx.set(13, living(Items.REDSTONE, 1));
        ctx.set(14, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRedstoneFunction.ID, Set.of(10, 11, 13, 14),
            LivingComparatorFunction.ID, Set.of(12));

        var data = propagate(ctx, slots);

        assertTrue(data.getSignal(13) > 0,
            "比较器紧邻输出格(粉13)应被供电，实际=" + data.getSignal(13));
        // 关键断言：再往后一格(粉14)也必须被供电；修复前只能传一格，此格为 0
        assertTrue(data.getSignal(14) > 0,
            "比较器输出应沿粉链传播到第二格(粉14)，修复前只能传一格，实际="
                + data.getSignal(14));
    }

    /**
     * 回归：比较器关闭后，下游粉链必须自然衰减归零（不能卡在高电平）。
     *
     * <p>布局（9 列第 2 行）：拉杆(10,朝比较器后方) → 比较器(11,朝右) → 粉(12) → 粉(13) → 粉(14)。</p>
     *
     * <p>背景：边模型重构时，比较器出边的写值从 phase3 的 {@code !=} 兜底改为
     * phase4 中 powerConductiveNeighbor 前直接 set（可抬可压）。若只抬不压，
     * 比较器关闭时出边不会归零，下游粉链下一 tick 仍读到高 maxInput 而卡死。
     * 本测试锁定「关闭 → 出边归零 → 粉链衰减归零」行为。</p>
     */
    @Test
    @DisplayName("比较器关闭后下游粉链自然衰减归零")
    void comparator_turnsOff_downstreamDustDecaysToZero() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ItemStack lever = living(Items.LEVER, 1);
        LivingItemManager.setLeverData(lever, new LivingLeverData(true));
        ctx.set(10, lever);
        ItemStack comparator = living(Items.COMPARATOR, 1);
        LivingItemManager.setComparatorData(comparator,
            LivingComparatorData.DEFAULT.withDirection(Pos2D.RIGHT));
        ctx.set(11, comparator);
        ctx.set(12, living(Items.REDSTONE, 1));
        ctx.set(13, living(Items.REDSTONE, 1));
        ctx.set(14, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingLeverFunction.ID, Set.of(10),
            LivingComparatorFunction.ID, Set.of(11),
            LivingRedstoneFunction.ID, Set.of(12, 13, 14));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        assertTrue(data.getSignal(14) > 0, "前置条件：开启时粉链末端应有信号");

        // 关闭拉杆（比较器失去后方输入）
        LivingItemManager.setLeverData(lever, new LivingLeverData(false));
        ctx.set(10, lever);

        // 反复传播足够多 tick，让衰减从比较器出边一路传到链末端
        for (int t = 0; t < 20; t++) {
            tickOnce(data, ctx, slots);
        }

        assertEquals(0, data.getSignal(11), "比较器出边应归零");
        assertEquals(0, data.getSignal(12), "下游粉应衰减归零");
        assertEquals(0, data.getSignal(13), "下游粉应衰减归零");
        assertEquals(0, data.getSignal(14), "下游粉应衰减归零");
    }

    // ════════════════════════════════════════
    // 多 tick 稳态：稳态跳过不得掐死容器内部信号源
    // ════════════════════════════════════════

    @Test
    @DisplayName("多 tick 稳态：红石块持续为相邻红石粉供能（稳态跳过不得熄灭）")
    void redstoneBlock_powersAdjacentDust_acrossSteadyTicks() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        ContainerRedstoneData data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        int first = data.getSignal(11);

        for (int t = 0; t < 10; t++) {
            tickOnce(data, ctx, slots);
        }

        assertEquals(15, first, "首 tick 红石块应为相邻红石粉供能 15");
        assertEquals(15, data.getSignal(11),
            "稳态跳过后红石块仍应持续供能（跳过次数=" + data.steadySkipCount + "）");
    }

    // ════════════════════════════════════════
    // Bug A 回归：比较器首尾相连（链式）传播
    // ════════════════════════════════════════

    /**
     * 回归 Bug A：活红石比较器首尾相连，至少传 4 级而不衰减到 0。
     *
     * <p>布局（9 列第 2 行）：红石块(9) → 粉(10) → 粉(11) → 比较器(12,右) →
     * 比较器(13,右) → 比较器(14,右) → 比较器(15,右) → 粉(16)。</p>
     *
     * <p>根因：元件输出依赖上游元件出边，而 {@code comparatorSlots} 是 HashSet
     * （哈希桶序，与几何无关），单遍遍历会按随机顺序截断链；又因 {@code reset()}
     * 每 tick 清零、且 {@code powerConductiveNeighbor} 不把元件当导体入队，
     * 断掉的那一级永不自愈（表现为「链到第 4 个就死」）。修复改为迭代到稳定。</p>
     */
    @Test
    @DisplayName("Bug A 回归：比较器正向链式（≥4 级）全部点亮")
    void bugA_comparatorChain_forward_allLit() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(9, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(10, living(Items.REDSTONE, 1));
        ctx.set(11, living(Items.REDSTONE, 1));
        for (int slot = 12; slot <= 15; slot++) {
            ItemStack c = living(Items.COMPARATOR, 1);
            LivingItemManager.setComparatorData(c,
                LivingComparatorData.DEFAULT.withDirection(Pos2D.RIGHT));
            ctx.set(slot, c);
        }
        ctx.set(16, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(9),
            LivingRedstoneFunction.ID, Set.of(10, 11, 16),
            LivingComparatorFunction.ID, Set.of(12, 13, 14, 15));

        var data = propagate(ctx, slots);

        // 链末端粉(16) 被第 4 个比较器(15) 供能 → 整条链都通
        assertTrue(data.getSignal(16) > 0,
            "第 4 个比较器应输出并点亮末端粉，修复前此处为 0，实际="
                + data.getSignal(16));
        // 中间每级比较器也应点亮其下游
        assertTrue(data.getSignal(12) > 0 && data.getSignal(15) > 0,
            "链式首尾比较器均应输出");
    }

    /**
     * 回归 Bug A：反向链（比较器朝左）同样全部点亮，确认不依赖几何方向。
     *
     * <p>布局：粉(10) → 比较器(11,左) → 比较器(12,左) → 比较器(13,左) →
     * 比较器(14,左) → 粉(15) → 粉(16) → 红石块(17)。</p>
     */
    @Test
    @DisplayName("Bug A 回归：比较器反向链式（≥4 级）全部点亮")
    void bugA_comparatorChain_reverse_allLit() {
        var ctx = new FakeContainerContext(SIZE, WIDTH);
        ctx.set(17, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(16, living(Items.REDSTONE, 1));
        ctx.set(15, living(Items.REDSTONE, 1));
        for (int slot = 11; slot <= 14; slot++) {
            ItemStack c = living(Items.COMPARATOR, 1);
            LivingItemManager.setComparatorData(c,
                LivingComparatorData.DEFAULT.withDirection(Pos2D.LEFT));
            ctx.set(slot, c);
        }
        ctx.set(10, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(17),
            LivingRedstoneFunction.ID, Set.of(15, 16, 10),
            LivingComparatorFunction.ID, Set.of(11, 12, 13, 14));

        var data = propagate(ctx, slots);

        assertTrue(data.getSignal(10) > 0,
            "反向链末端粉(10) 应被第 4 个比较器(11) 供能，修复前为 0，实际="
                + data.getSignal(10));
        assertTrue(data.getSignal(11) > 0 && data.getSignal(14) > 0,
            "反向链式首尾比较器均应输出");
    }

    // ════════════════════════════════════════
    // Bug B 回归：容器修订计数停滞 → 信号层「集体死掉」
    // ════════════════════════════════════════

    /**
     * 回归 Bug B：内容经原版途径变更（不经 {@code setItem}，不 bump 修订计数）
     * 时，{@link ContainerLivingItemHandler#syncContentRevision} 用内容签名兜底
     * bump 修订计数，使稳态跳过被打破、信号层重新工作。
     *
     * <p>根因：{@code bumpContainerRevision} 只被本模组的 {@code setItem} /
     * {@code syncSlotToClients} 调用；原版玩家点击、漏斗、掉落物拾取直接改底层容器，
     * 完全绕开 → 修订计数停滞 → 稳态跳过永不打破、快照缓存永不重建，容器内信号层
     * 像「全死了一样」，只有外接跨容器信号改变 {@code externalSig} 时才暂时活过来。</p>
     */
    @Test
    @DisplayName("Bug B 回归：原版途径改内容后，内容签名兜底 bump 修订计数")
    void bugB_contentChangedViaVanillaPath_bumpsRevision() {
        // 唯一 containerKey，避免与同 JVM 内其它测试共享静态修订计数映射
        var ctx = new FakeContainerContext(SIZE, WIDTH,
            "bugB_" + UUID.randomUUID());

        // 首次写入经 set()（会 bump），建立基线签名
        ctx.set(0, living(Items.REDSTONE, 1));
        ContainerLivingItemHandler.syncContentRevision(ctx);
        long baseline = ContainerLivingItemHandler.getContainerRevision(ctx);

        // 后续 tick 内容不变 → 不应再 bump
        ContainerLivingItemHandler.syncContentRevision(ctx);
        assertEquals(baseline, ContainerLivingItemHandler.getContainerRevision(ctx),
            "内容不变时 syncContentRevision 不应 bump 修订计数");

        // 模拟原版途径：直接改底层容器，不经 setItem（不 bump 修订计数）
        ctx.rawSet(0, ItemStack.EMPTY);

        // 修复前：rev 不变 → 稳态跳过永不打破（信号层集体死掉）。
        // 修复后：syncContentRevision 经内容签名检出变化 → bump。
        ContainerLivingItemHandler.syncContentRevision(ctx);
        assertTrue(ContainerLivingItemHandler.getContainerRevision(ctx) > baseline,
            "原版途径改内容后，syncContentRevision 必须兜底 bump 修订计数");
    }

    /**
     * 回归 Bug B（集成视角）：原版途径移除信号源后，配合每 tick 的
     * {@code syncContentRevision} 兜底，稳态跳过应被打破、残留信号归零。
     *
     * <p>等价的生产路径是 {@code ContainerLivingItemHandler.processContext} 在槽位扫描后
     * 调用 {@code syncContentRevision}；此处直接调用同一方法以驱动真实红石层。</p>
     */
    @Test
    @DisplayName("Bug B 回归：原版途径移除信号源后，稳态跳过失效、信号归零")
    void bugB_vanillaRemoval_reflectedAfterSyncContentRevision() {
        var ctx = new FakeContainerContext(SIZE, WIDTH,
            "bugB_integration_" + UUID.randomUUID());
        ctx.set(10, living(Items.REDSTONE_BLOCK, 1));
        ctx.set(11, living(Items.REDSTONE, 1));

        var slots = Map.<String, Set<Integer>>of(
            LivingRedstoneBlockFunction.ID, Set.of(10),
            LivingRedstoneFunction.ID, Set.of(11));

        var data = new ContainerRedstoneData();
        tickOnce(data, ctx, slots);
        assertTrue(data.getSignal(11) > 0, "前置：红石块应为相邻红石粉供能");

        // 模拟原版途径移除红石块（不经 setItem → 不 bump 修订计数）
        ctx.rawSet(10, ItemStack.EMPTY);

        // 生产路径每 tick 调用 syncContentRevision 兜底
        ContainerLivingItemHandler.syncContentRevision(ctx);
        tickOnce(data, ctx, slots);

        assertEquals(0, data.getSignal(11),
            "原版途径移除信号源后，稳态跳过应被打破、残留信号必须归零");
    }
}
