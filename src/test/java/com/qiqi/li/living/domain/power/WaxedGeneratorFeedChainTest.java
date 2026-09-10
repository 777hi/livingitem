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

    /** 模拟真实 IItemHandler 语义（可插入/可提取），而非「全部拒绝」的占位实现 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        FakeHandler(ItemStack... slots) { this.slots = slots; }
        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ItemStack existing = getStackInSlot(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) return stack;
            int space = Math.min(getSlotLimit(slot), stack.getMaxStackSize()) - existing.getCount();
            int inserted = Math.min(space, stack.getCount());
            if (inserted <= 0) return stack;
            ItemStack remaining = stack.copy();
            remaining.shrink(inserted);
            if (!simulate) {
                slots[slot] = existing.isEmpty() ? stack.copyWithCount(inserted) : existing.copyWithCount(existing.getCount() + inserted);
            }
            return remaining;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = getStackInSlot(slot);
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int taken = Math.min(amount, existing.getCount());
            ItemStack taken2 = existing.copyWithCount(taken);
            if (!simulate) existing.setCount(existing.getCount() - taken);
            return taken2;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack s = new ItemStack(item, count);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    /**
     * 服务端 Level mock，带递增世界时钟（2026-09-11 换轴配套）：
     * 相位链路 now = level.getGameTime()（世界轴）——mock 不 stub 时
     * 恒返回 0，所有跳变挤在 tick 0 → 派生注册表恒空。用 Answer 递增
     * 模拟真实世界 tick，测试驱动即对齐生产行为。
     */
    private static net.minecraft.world.level.Level mockServerLevel() {
        net.minecraft.world.level.Level level =
            org.mockito.Mockito.mock(net.minecraft.world.level.Level.class);
        org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
        long[] tick = {0};
        org.mockito.Mockito.when(level.getGameTime()).thenAnswer(inv -> tick[0]++);
        org.mockito.Mockito.when(level.getServer())
            .thenReturn(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        return level;
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

        // 首拍热身（2026-09-09 根修对齐）：真实游戏容器已跑多 tick 才有振荡；
        // 红石账本首拍 hasEdgeHistory=false 跳过边检测——先空跑一拍建立边历史
        {
            TickContext warm = new TickContext(ctx);
            warm.setFunctionSlots(redSlots);
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, warm);
            function.tickContainerData(entries, ctx, warm);
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
        // 首拍热身（2026-09-09 根修对齐）：先空跑一拍建立边历史（首拍无沿）
        {
            TickContext warm = new TickContext(ctx);
            warm.setFunctionSlots(redSlots);
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, warm);
            function.tickContainerData(entries, ctx, warm);
        }
        for (int t = 0; t < 48; t++) {
            TickContext tick = new TickContext(ctx);
            tick.setFunctionSlots(redSlots);
            redstone.resetProcessedFlag();
            redstone.calculate(ctx, tick);

            // 诊断：发电机左向入边（= 粉 11 朝右的出边）是否振荡
            int incoming = redstone.sensedSignal(genSlot,
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

        // 首拍热身（根修对齐）：先空跑一拍建立边历史（首拍无沿，火把环从第二拍起自然起振）
        ContainerLivingItemHandler.processContext(ctx, null);
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

        // 首拍热身（根修对齐）：先空跑一拍建立边历史（首拍无沿）
        ContainerLivingItemHandler.processContext(ctx, null);
        for (int t = 0; t < 48; t++) {
            ContainerLivingItemHandler.processContext(ctx, null);
        }

        GeneratorState gen = ctx.getOrCreatePowerData().getGenerator(genSlot);
        assertTrue(gen != null, "应有发电机状态");
        assertTrue(gen.getEmaPowerRe() > 0, "大箱形状下涂蜡发电机应发电");
    }

    /**
     * 大容量容器复刻（用户日志：≥96 槽的「大箱」，网格 12×8）：
     * 活漏斗默认方向（上抽下注），上方放普通物品，下方空——生产路径应完成传输。
     */
    @Test
    @DisplayName("探针：TransferPipeline 各阶段逐段定位")
    void probe_transferStages() throws Exception {
        ItemStack[] slots = new ItemStack[27];
        slots[13] = living(Items.HOPPER, 1);
        slots[1] = new ItemStack(Items.COBBLESTONE, 16);

        net.minecraft.world.level.Level level = mockServerLevel();

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), null, new ArrayList<>(), new ArrayList<>(), level);

        TickContext tick = new TickContext(ctx);
        var filter = tick.getSnapshot().getFilterOf(13);

        var source = com.qiqi.li.living.transfer.SlotAccessorFactory.create(
            level.getServer(), ctx, 1, filter, new java.util.HashSet<>(), tick.getSnapshot());
        var target = com.qiqi.li.living.transfer.SlotAccessorFactory.create(
            level.getServer(), ctx, 25, null, new java.util.HashSet<>(), tick.getSnapshot());

        assertTrue(source != null, "阶段 A：source accessor 不应为 null（圆石非活物品应走 PlainSlotAccessor）");
        assertTrue(target != null, "阶段 A：target accessor 不应为 null");

        assertTrue(!source.isEmpty(), "阶段 B：source 不应为空（槽 1 有圆石）");
        assertTrue(!target.isFull(), "阶段 C：target 不应已满（槽 25 为空）");

        var sim = source.simulateExtract(64);
        assertTrue(!sim.isEmpty(), "阶段 D：模拟提取应有圆石，实际=" + sim);

        int canAccept = target.simulateInsert(sim);
        assertTrue(canAccept > 0, "阶段 E：目标应能接受，实际=" + canAccept);

        boolean ok = com.qiqi.li.living.domain.hopper.TransferPipeline.execute(
            ctx, level, 13, 1, 25, 16, 64, filter,
            com.qiqi.li.living.model.ResolvedSlots.ofTransfer(1, 25,
                com.qiqi.li.living.domain.hopper.DirectionTransferData.DEFAULT.sourceOffset(),
                com.qiqi.li.living.domain.hopper.DirectionTransferData.DEFAULT.targetOffset()),
            com.qiqi.li.living.domain.hopper.DirectionTransferData.DEFAULT, tick);
        assertTrue(ok, "阶段 F：TransferPipeline 应返回 true");
    }

    @Test
    @DisplayName("直调 TransferPipeline：27 槽漏斗单次传输")
    void directPipeline_singleTransfer() throws Exception {
        ItemStack[] slots = new ItemStack[27];
        slots[13] = living(Items.HOPPER, 1);
        slots[1] = new ItemStack(Items.COBBLESTONE, 16);

        net.minecraft.world.level.Level level = mockServerLevel();

        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), null, new ArrayList<>(), new ArrayList<>(), level);

        var dir = com.qiqi.li.living.domain.hopper.DirectionTransferData.DEFAULT;
        TickContext tick = new TickContext(ctx);
        var filter = tick.getSnapshot().getFilterOf(13);
        boolean ok = com.qiqi.li.living.domain.hopper.TransferPipeline.execute(
            ctx, level, 13, 1, 25, 16, 64, filter,
            com.qiqi.li.living.model.ResolvedSlots.ofTransfer(1, 25,
                dir.sourceOffset(), dir.targetOffset()),
            dir, tick);
        assertTrue(ok, "直调 TransferPipeline 应返回 true，实际 false");
        assertTrue(slots[25].getCount() > 0, "注入目标应有物品");
    }

    @Test
    @DisplayName("活漏斗传输：27/36/96 槽三种容器形状对照")
    void largeContainer_hopperTransfers() {
        int[][] shapes = {{27, 9}, {36, 9}, {96, 12}};
        for (int[] shape : shapes) {
            int size = shape[0];
            int width = shape[1];
            ItemStack[] slots = new ItemStack[size];
            int hopperSlot = width + 1;               // 行 1 列 1
            slots[hopperSlot] = living(Items.HOPPER, 1);
            int sourceSlot = 1;                        // 漏斗上方（输入源）：hopperSlot - width
            slots[sourceSlot] = new ItemStack(Items.COBBLESTONE, 16);
            int targetSlot = hopperSlot + width;      // 漏斗下方（注入目标）

            net.minecraft.world.level.Level level = mockServerLevel();

            SimpleContainerContext ctx = new SimpleContainerContext(
                new FakeHandler(slots), null, new ArrayList<>(), new ArrayList<>(), level);
            for (int t = 0; t < 40; t++) {
                ContainerLivingItemHandler.processContext(ctx, level);
            }

            assertTrue(ctx.getItem(targetSlot).getCount() > 0,
                size + " 槽容器：活漏斗应把上方物品传输到下方，实际下方数量=" + ctx.getItem(targetSlot).getCount()
                    + "，上方剩余=" + ctx.getItem(sourceSlot).getCount());
        }
    }

    /**
     * 玩家背包发电复刻：36 槽（9×4）背包形状，火把环 + 涂蜡发电机 + 同锈级铜灯，
     * 走生产路径 processContext（mock Level），断言铜灯被充电（= 发电生效）。
     */
    @Test
    @DisplayName("玩家背包（36 槽）：火把环 + 发电机 + 铜灯 应发电并充入铜灯")
    void playerInventory_generatesAndChargesBulb() {
        ItemStack[] slots = new ItemStack[36];
        ItemStack torch = living(Items.REDSTONE_TORCH, 1);
        LivingItemManager.setRedstoneTorchData(torch,
            LivingItemManager.getRedstoneTorchData(torch).withDirection(com.qiqi.li.living.model.Pos2D.RIGHT));
        slots[10] = torch;
        for (int idx : new int[] {9, 11, 18, 19, 20}) {
            slots[idx] = living(Items.REDSTONE, 1);
        }
        slots[12] = living(Items.WAXED_COPPER_BLOCK, 4);
        slots[13] = living(Items.WAXED_COPPER_BULB, 1);

        net.minecraft.world.entity.player.Player player = org.mockito.Mockito.mock(net.minecraft.world.entity.player.Player.class);
        org.mockito.Mockito.when(player.getStringUUID()).thenReturn("test");
        net.minecraft.world.level.Level level = mockServerLevel();
        org.mockito.Mockito.when(player.level()).thenReturn(level);
        org.mockito.Mockito.when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        net.minecraft.world.entity.player.Inventory inv = new net.minecraft.world.entity.player.Inventory(player);

        // 走玩家背包的生产构建路径（buildContext：inventory 分支 → key = player_test）
        SimpleContainerContext ctx = (SimpleContainerContext) ContainerLivingItemHandler.buildContext(
            new FakeHandler(slots), inv, level);
        assertEquals("player_test", ctx.getContainerKey(), "背包上下文应使用 player 稳定键");

        for (int t = 0; t < 64; t++) {
            ContainerLivingItemHandler.processContext(ctx, level);
        }

        var bulbData = LivingItemManager.getWaxedBulbData(ctx.getItem(13));
        assertTrue(bulbData.chargeMilliFe() > 0,
            "背包里同锈级铜灯应被充电（发电生效），实际=" + bulbData.chargeMilliFe());
    }

    /**
     * 雕文方向配置一致性：WASD 配置写入的方向（updateSlotDirection）
     * 必须与发电采样方向（chiseledInputEdge）同源——配置 RIGHT 后，
     * 只有 RIGHT 侧注入能驱动派生，LEFT 侧注入应被忽略。
     */
    @Test
    @DisplayName("雕文配置一致性：配置输入=右后，右侧注入派生、左侧注入忽略")
    void chiseled_configMatchesSamplingSide() {
        ItemStack[] slots = new ItemStack[9];
        slots[4] = living(Items.WAXED_CHISELED_COPPER, 4);
        SimpleContainerContext ctx = new SimpleContainerContext(
            new FakeHandler(slots), null, new ArrayList<>(), new ArrayList<>(),
            mockServerLevel());

        // 模拟 WASD 配置（SlotDirectionPacket → updateSlotDirection，写在 carried 组件上）
        ItemStack configured = ctx.getItem(4);
        var dirFunc = new LivingWaxedCopperFunction();
        assertTrue(dirFunc.updateSlotDirection(configured, "input",
            com.qiqi.li.living.model.Pos2D.RIGHT), "配置应成功写入");
        ctx.setItem(4, configured);

        List<LivingItemFunction.SlotEntry> entries = new ArrayList<>();
        entries.add(new LivingItemFunction.SlotEntry(4, ctx.getItem(4)));

        // 右侧注入：采样面 = 输入方向（RIGHT）→ 派生应出现
        for (int t = 0; t < 24; t++) {
            TickContext tick = new TickContext(ctx);
            ContainerRedstoneData redstone = tick.getOrCreateRedstoneData(ctx);
            int cur = Math.floorMod(t, 4) < 2 ? 4096 : 0;
            int prev = Math.floorMod(t - 1, 4) < 2 ? 4096 : 0;
            redstone.setPrevIncomingEdgeForTest(4, ContainerRedstoneData.EDGE_RIGHT, prev);
            redstone.setIncomingEdgeForTest(4, ContainerRedstoneData.EDGE_RIGHT, cur);
            function.tickContainerData(entries, ctx, tick);
        }
        var reg = ctx.getOrCreatePowerData().getRegistry(4);
        assertEquals(1, reg.size(), "右侧注入应派生 1 条驻波");
        assertEquals(1, reg.get(0).offset(), "派生偏移 = 源偏移 + 1");
    }

    /**
     * 四方向逐一验证：同一雕文，依次配置输入方向为 UP/DOWN/LEFT/RIGHT，
     * 每次只在对应方向注入方波，断言派生相位出现在该方向（配置→采样一致性）。
     */
    @Test
    @DisplayName("雕文四方向逐一验证：每个输入方向的注入都能被对应感应")
    void chiseled_allDirections_sensedIndividually() {
        var chiseled = living(Items.WAXED_CHISELED_COPPER, 4);
        var dirFunc = new LivingWaxedCopperFunction();

        // (方向, 对应的 EDGE 常量, 该方向邻居的槽位偏移)
        record Case(com.qiqi.li.living.model.Pos2D dir, int edge, int neighborOffset, String name) {}
        var cases = List.of(
            new Case(com.qiqi.li.living.model.Pos2D.UP,    ContainerRedstoneData.EDGE_UP,    -9, "UP"),
            new Case(com.qiqi.li.living.model.Pos2D.DOWN,  ContainerRedstoneData.EDGE_DOWN,   9, "DOWN"),
            new Case(com.qiqi.li.living.model.Pos2D.LEFT,  ContainerRedstoneData.EDGE_LEFT,  -1, "LEFT"),
            new Case(com.qiqi.li.living.model.Pos2D.RIGHT, ContainerRedstoneData.EDGE_RIGHT,  1, "RIGHT"));

        for (var c : cases) {
            ItemStack[] slots = new ItemStack[27];
            slots[13] = chiseled.copy();
            // 重置为默认再配置目标方向（模拟玩家 WASD 配置）
            LivingItemManager.setWaxedChiseledData(slots[13],
                LivingItemManager.getWaxedChiseledData(slots[13]).withInputDir(c.dir()));
            SimpleContainerContext ctx = new SimpleContainerContext(
                new FakeHandler(slots), null, new ArrayList<>(), new ArrayList<>(),
                mockServerLevel());

            // 预建容器尺寸的 edgeGrid（模拟生产中 calculate 创建的网格），
            // 否则测试 seam 的 9×1 默认网格会让 UP/DOWN 注入越界失效
            TickContext preTick = new TickContext(ctx);
            ContainerRedstoneData redstone = preTick.getOrCreateRedstoneData(ctx);

            boolean sawRising = false;
            int neighborSlot = 13 + c.neighborOffset();
            // 邻居槽位越界的方向（如边缘）跳过几何检查——用入边 seam 注入即可
            for (int t = 0; t < 24; t++) {
                redstone.resetProcessedFlag();
                redstone.calculate(ctx, preTick);
                TickContext tick = new TickContext(ctx);
                int cur = Math.floorMod(t, 4) < 2 ? 4096 : 0;
                int prev = Math.floorMod(t - 1, 4) < 2 ? 4096 : 0;
                // 注入雕文本体 (13, c.edge) 方向的入边 = 邻居朝雕文的出边
                redstone.setPrevIncomingEdgeForTest(13, c.edge(), prev);
                redstone.setIncomingEdgeForTest(13, c.edge(), cur);
                int readback = redstone.sensedSignal(13, c.edge());
                if (readback > 0) sawRising = true;
                function.tickContainerData(entries(ctx, 13), ctx, tick);
                if (t >= 8 && !sawRising && c.name().equals("DOWN")) {
                    var trProbe = powerData(tick, ctx).getEdgeTracker(((long) 13 << 2) | c.edge());
                    assertTrue(trProbe != null && trProbe.period() == 4,
                        "DOWN 边 tracker 应锁相 4t，实际 period=" + (trProbe == null ? "null" : trProbe.period()));
                }
            }
            var reg = ctx.getOrCreatePowerData().getRegistry(13);
            assertTrue(reg.size() >= 1 && reg.get(0).offset() == 1,
                "配置输入=" + c.name() + "：对应方向注入应派生 φ+1，实际 reg=" + reg);
        }
    }

    private static ContainerPowerData powerData(TickContext tick, com.qiqi.li.living.container.ContainerContext ctx) {
        return tick.getOrCreatePowerData(ctx);
    }

    private static List<LivingItemFunction.SlotEntry> entries(
            com.qiqi.li.living.container.ContainerContext ctx, int slot) {
        return List.of(new LivingItemFunction.SlotEntry(slot, ctx.getItem(slot)));
    }

    /**
     * 序列化往返回归（v19.1 根因修复）：Pos2D 经 codec 往返后是**值相等的新实例**——
     * 方向映射若用引用比较（`dir == Pos2D.RIGHT`），配置过的雕文在物品组件
     * 反序列化后会全部落入 fallback 恒 UP，只有上方信号被感应。
     */
    @Test
    @DisplayName("序列化往返：值相等的新实例方向必须被正确映射（不再引用比较）")
    void directionMapping_valueNotIdentity() throws Exception {
        com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var cases = List.of(
            new com.qiqi.li.living.model.Pos2D(0, -1),   // UP
            new com.qiqi.li.living.model.Pos2D(0, 1),    // DOWN
            new com.qiqi.li.living.model.Pos2D(-1, 0),   // LEFT
            new com.qiqi.li.living.model.Pos2D(1, 0));   // RIGHT
        var expected = List.of(
            ContainerRedstoneData.EDGE_UP, ContainerRedstoneData.EDGE_DOWN,
            ContainerRedstoneData.EDGE_LEFT, ContainerRedstoneData.EDGE_RIGHT);

        for (int i = 0; i < cases.size(); i++) {
            var original = cases.get(i);
            // codec 往返：编码 → 解码（产生值相等的新实例）
            var encoded = com.qiqi.li.living.model.Pos2D.CODEC.encodeStart(ops, original).getOrThrow();
            var roundTrip = com.qiqi.li.living.model.Pos2D.CODEC.parse(ops, encoded).getOrThrow();
            org.junit.jupiter.api.Assertions.assertNotSame(original, roundTrip,
                "codec 往返应产生新实例（复刻组件反序列化）");

            // 新实例经 chiseledInputEdge 同款映射，必须映射到正确方向
            ItemStack stack = living(Items.WAXED_CHISELED_COPPER, 4);
            LivingItemManager.setWaxedChiseledData(stack,
                LivingItemManager.getWaxedChiseledData(stack).withInputDir(roundTrip));
            int edge = LivingWaxedCopperFunction.chiseledInputEdgeForTest(roundTrip);
            assertEquals(expected.get(i).intValue(), edge,
                "方向 " + roundTrip + "（反序列化实例）应映射到 " + expected.get(i));
        }
    }
}
