package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * {@link ContainerFluidData} 行为快照（golden master，2026-10-03）。
 *
 * <p><b>为什么先写这个</b>：本类是容器级流体引擎，此前<b>零单测</b>。1b-1「引擎泛化」
 * （给条目加流体类型维度）是纯重构 —— 但「397 绿」只证明<b>别的层</b>没坏，
 * 证明不了<b>引擎行为</b>没变。故先把当前「水」行为钉死，重构后必须逐条不变。</p>
 *
 * <p><b>容器约定</b>：9 格 handler ⇒ 宽度推断为 9 ⇒ <b>1×9 单行</b>，扩散沿行向右
 * （{@code getNeighbors} 顺序：左 / 右 / 上 / 下，此处只有右）。</p>
 */
class ContainerFluidDataTest {

    /**
     * 测试用 IItemHandler —— 内存槽位数组。
     *
     * <p>⚠️ <b>必须实现真实 insert/extract 语义</b>：{@code SimpleContainerContext.setItem}
     * 走的是 {@code insertItem}（不是 {@code setStackInSlot}），空实现会让「推物品」静默失败
     * （表现为 {@code setItem: ... 未能插入槽位 N} 警告）。</p>
     */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(int size) {
            slots = new ItemStack[size];
            for (int i = 0; i < size; i++) slots[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            int maxStack = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                int toInsert = Math.min(stack.getCount(), maxStack);
                if (!simulate) slots[slot] = stack.copyWithCount(toInsert);
                ItemStack remainder = stack.copy();
                remainder.shrink(toInsert);
                return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = maxStack - existing.getCount();
                if (space <= 0) return stack;
                int toInsert = Math.min(stack.getCount(), space);
                if (!simulate) slots[slot].grow(toInsert);
                ItemStack remainder = stack.copy();
                remainder.shrink(toInsert);
                return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = slots[slot];
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) slots[slot].shrink(toExtract);
            return result;
        }

        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    private static ItemStack livingWaterBucket() {
        ItemStack s = new ItemStack(Items.WATER_BUCKET);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    private static ItemStack livingNonWater() {
        ItemStack s = new ItemStack(Items.REDSTONE);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    private SimpleContainerContext row(ItemStack... contents) {
        FakeHandler h = new FakeHandler(9);
        for (int i = 0; i < contents.length; i++) h.slots[i] = contents[i];
        return new SimpleContainerContext(h);
    }

    /**
     * 测试自足：基线注册水为「会流动，上限 7」—— 不依赖 mod 引导，值同 {@code WaterRegistration}。
     * （{@link FluidFlowBehaviors} 是静态注册表 ⇒ 必须显式重置，项目红线。）
     */
    @BeforeEach
    void baselineWaterBehavior() {
        FluidFlowBehaviors.clear();   // 清其它测试残留的注册（㉗ 的慢岩浆等）—— 静态表必须显式 reset
        registerWater(ContainerFluidData.MAX_FLOW_LEVEL);
    }

    @AfterEach
    void restoreWaterBehavior() {
        registerWater(ContainerFluidData.MAX_FLOW_LEVEL);
    }

    private static void registerWater(int maxLevel) {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), FluidFlowBehavior.flowing(maxLevel, 0));
    }

    @Test
    @DisplayName("① 单源沿行扩散：level 0..7，第 8 格（level 8）不到达")
    void singleSource_spreadsToMaxLevel7() {
        var ctx = row();
        assertEquals(9, ctx.getWidth(), "9 格应为 1×9 单行");

        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        var flows = fluid.getFlows();
        assertEquals(8, flows.size(), "应恰好覆盖 slot 0..7（level 0..7）");
        assertEquals(0, flows.get(0).level());
        assertTrue(flows.get(0).isSource(), "slot 0 是源");
        for (int i = 1; i <= 7; i++) {
            assertEquals(i, flows.get(i).level(), "slot " + i + " 应为 level " + i);
            assertFalse(flows.get(i).isSource(), "slot " + i + " 是流动水不是源");
            assertEquals(i - 1, flows.get(i).fromSlot(), "slot " + i + " 的父节点应为 " + (i - 1));
        }
        assertFalse(flows.containsKey(8), "level 8 超过上限，slot 8 不应有水");
    }

    @Test
    @DisplayName("② 活物品阻挡扩散")
    void livingItemBlocksSpread() {
        var ctx = row(ItemStack.EMPTY, ItemStack.EMPTY, livingNonWater());
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        var flows = fluid.getFlows();
        assertEquals(2, flows.size(), "slot 2 是活物品 ⇒ 水只到 slot 1");
        assertTrue(flows.containsKey(0));
        assertTrue(flows.containsKey(1));
        assertFalse(flows.containsKey(2), "活物品格不应进水");
    }

    @Test
    @DisplayName("③ 非活物品不阻挡（水穿过）")
    void nonLivingItemDoesNotBlock() {
        var ctx = row(new ItemStack(Items.REDSTONE, 3));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        var flows = fluid.getFlows();
        assertEquals(8, flows.size(), "非活物品不阻挡 ⇒ 仍扩散到 slot 7");
        assertEquals(1, flows.get(1).level());
    }

    @Test
    @DisplayName("④ 源移除后（汲走）流动整体消失")
    void removedSource_clearsFlows() {
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);
        assertFalse(fluid.getFlows().isEmpty(), "先确认有水");

        fluid.removeGeneratedSource(0);
        fluid.tick(ctx);
        assertTrue(fluid.getFlows().isEmpty(), "源移除 ⇒ 无播种 ⇒ 全空");
    }

    @Test
    @DisplayName("⑤ 空容器（无源）无流动")
    void noSource_noFlows() {
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.tick(ctx);
        assertTrue(fluid.getFlows().isEmpty());
    }

    @Test
    @DisplayName("⑥ 水流推动：随该流体的蔓延**同拍**推动（瞬时水 = 每拍推进一格）")
    void pushItems_movesItemDownstreamWithGrowth() {
        var ctx = row(ItemStack.EMPTY, new ItemStack(Items.REDSTONE, 1));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());

        fluid.tick(ctx);
        assertEquals(Items.REDSTONE, ctx.getItem(2).getItem(), "第 1 拍：随蔓延同拍推到下游 slot 2");
        assertTrue(ctx.getItem(1).isEmpty(), "slot 1 的物品应被推走");

        for (int t = 0; t < 3; t++) fluid.tick(ctx);
        assertEquals(Items.REDSTONE, ctx.getItem(5).getItem(), "每拍一格 ⇒ 4 拍后到 slot 5");
    }

    @Test
    @DisplayName("⑦ 行为接缝：会流动流体按各自 maxLevel 扩散（改 maxLevel=3 ⇒ 只到 slot 3）")
    void behaviorSeam_respectsMaxLevel() {
        registerWater(3);
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(4, fluid.getFlows().size(), "maxLevel=3 ⇒ 覆盖 slot 0..3");
        assertFalse(fluid.getFlows().containsKey(4), "slot 4 超过 maxLevel=3");
    }

    @Test
    @DisplayName("⑧ 行为接缝：静止流体只做源、不扩散")
    void behaviorSeam_staticDoesNotSpread() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), FluidFlowBehavior.STATIC);
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(1, fluid.getFlows().size(), "静止 ⇒ 只有源自己");
        assertTrue(fluid.getFlows().get(0).isSource());
    }

    @Test
    @DisplayName("⑨ 集成回归：容器 tick 开始时创建流体数据（1a-4 漏建 ⇒ 水流失效）")
    void tickContext_createsFluidData() {
        var ctx = row();
        var tick = new TickContext(ctx);
        // 2026-10-08 计划 ⑤：创建从「TickContext 构造」移到领域钩子（由 setTickContext 触发）。
        // 这里直接调领域访问器 —— 等价于生产路径上钩子所做的事（测试自足，不依赖 mod 引导注册）。
        ContainerFluidHandler.getOrCreateFluidData(ctx);
        assertNotSame(ContainerFluidData.EMPTY, tick.data(ContainerFluidData.KEY),
            "1a-4 曾漏掉创建 ⇒ 流体数据无处着落 ⇒ 水流失效");
    }

    @Test
    @DisplayName("⑩ 通用驱动：自维持 + prio 0 + 不挂物品（纯容器级）")
    void fluidDriver_isSelfSustainingContainerLevel() {
        var driver = new LivingFluidFunction();
        assertTrue(driver.shouldTickWithoutOwnItems(row()), "必须自维持 —— 纯源容器的关键");
        assertEquals(0, driver.getPriority(), "prio 0：BFS 先于应力(1)/红石(2)");
        assertEquals("living_fluid", driver.getFunctionId());
        assertFalse(driver.canApply(new ItemStack(Items.WATER_BUCKET)), "不挂任何物品");
    }

    @Test
    @DisplayName("⑪ 通用驱动：tickContainerData 驱动 BFS（与桶解耦）")
    void fluidDriver_drivesBfs() {
        var ctx = row();
        var tick = new TickContext(ctx);
        // 2026-10-08 计划 ⑤：流体数据创建从「TickContext 构造」移到领域钩子（setTickContext 触发）——
        // 测试自足，直接调领域访问器（等价于生产路径上钩子所做的事）。
        ContainerFluidHandler.getOrCreateFluidData(ctx);
        var fluid = ctx.peekContainerData(ContainerFluidData.KEY);
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());

        new LivingFluidFunction().tickContainerData(List.of(), ctx, tick);

        assertEquals(8, fluid.getFlows().size(), "驱动应完成 BFS（slot 0..7）");
        assertTrue(fluid.getFlows().get(0).isSource());
    }

    @Test
    @DisplayName("⑫ 二维扩散：宽度>1 时右邻与下邻都蔓延（覆盖 getNeighbors 的 up/down）")
    void twoDimensional_spreadsRightAndDown() {
        // 27 格 ⇒ 宽度 9 ⇒ 3×9；源在 slot 0 ⇒ 右邻 slot 1、下邻 slot 9
        FakeHandler h = new FakeHandler(27);
        h.slots[0] = ItemStack.EMPTY;
        var ctx = new SimpleContainerContext(h);

        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        var flows = fluid.getFlows();
        assertEquals(0, flows.get(0).level());
        assertTrue(flows.get(0).isSource());
        assertEquals(1, flows.get(1).level(), "右邻应为 level 1");
        assertEquals(1, flows.get(9).level(), "下邻应为 level 1（二维蔓延）");
    }

    @Test
    @DisplayName("⑬ 源查询 API：isSource / sourceFluid / hasAnySource（供汲/倒处理器用）")
    void sourceQueryApi() {
        var ctx = row();
        var fluid = new ContainerFluidData();
        assertFalse(fluid.hasAnySource(), "空数据无源");

        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        assertTrue(fluid.isGeneratedSource(0), "注册即入派生源集合（flow 表等 tick 播种）");
        assertFalse(fluid.isSource(5), "非源槽位");
        assertNull(fluid.sourceFluid(5), "非源槽位无流体");

        fluid.tick(ctx);
        assertTrue(fluid.isSource(0), "源仍是源");
        assertFalse(fluid.isSource(1), "slot 1 是流动水不是源");
        assertNull(fluid.sourceFluid(1), "流动格不是源");

        fluid.removeGeneratedSource(0);
        assertFalse(fluid.isGeneratedSource(0), "派生源集合立即移除");
        fluid.tick(ctx);
        assertFalse(fluid.isSource(0), "下一拍重播种 ⇒ flow 表也不再是源");
        assertNull(fluid.sourceFluid(0));
    }

    // ── 派生源（活水源）—— 2026-10-03 F1 ─────────────────────

    @Test
    @DisplayName("⑭ 派生源独立存活：无任何活水桶，源与流动照样推进且跨 tick 保持")
    void generatedSource_survivesWithoutBucket() {
        var ctx = row();   // 空容器
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        assertTrue(fluid.hasGeneratedSources());

        fluid.tick(ctx);
        assertTrue(fluid.isSource(3), "派生源是源");
        assertEquals(9, fluid.getFlows().size(), "无桶也应 BFS 蔓延：源在 slot 3 ⇒ 9 格全可达");

        fluid.tick(ctx);
        fluid.tick(ctx);
        assertTrue(fluid.isSource(3), "水桶不在场派生源不消失（容器级永久资产）");
        assertEquals(9, fluid.getFlows().size());
    }

    @Test
    @DisplayName("⑮ 挤没：活物品进入派生源格 ⇒ 源永久销毁")
    void generatedSource_squeezedByLivingItem() {
        var ctx = row(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, livingNonWater());
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(3, Fluids.WATER.getFluidType());

        fluid.tick(ctx);
        assertFalse(fluid.hasGeneratedSources(), "派生源被挤没");
        assertFalse(fluid.isSource(3), "源格被活物品占据 ⇒ 无源");
        assertTrue(fluid.getFlows().isEmpty(), "无源 ⇒ 无流动");
    }

    @Test
    @DisplayName("⑯ 非活物品与派生源共存（水穿过实体，不挤没）")
    void generatedSource_coexistsWithNonLivingItem() {
        var ctx = row(new ItemStack(Items.REDSTONE, 3));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());

        fluid.tick(ctx);
        assertTrue(fluid.hasGeneratedSources(), "非活物品不挤没");
        assertTrue(fluid.isSource(0));
        assertEquals(8, fluid.getFlows().size(), "扩散不受同格物品影响");
    }

    @Test
    @DisplayName("⑰ 挤没无豁免：活水桶压进派生源格 ⇒ 源销毁（桶源已退役，无「接管」）")
    void bucketOnGeneratedSource_squeezesIt() {
        var ctx = row(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, livingWaterBucket());
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(3, Fluids.WATER.getFluidType());

        fluid.tick(ctx);
        assertFalse(fluid.isGeneratedSource(3), "活水桶（任何活物品）挤没派生源");
        assertFalse(fluid.isSource(3), "源随挤没消失");
        assertTrue(fluid.getFlows().isEmpty());
    }

    @Test
    @DisplayName("⑱ 同槽异种覆盖：岩浆派生源不扩散（未注册行为=静止），重注册水后整条覆盖")
    void generatedSource_fluidOverwrite() {
        var ctx = row();
        var fluid = new ContainerFluidData();

        fluid.registerGeneratedSource(3, Fluids.LAVA.getFluidType());
        fluid.tick(ctx);
        assertTrue(fluid.isSource(3));
        assertEquals(1, fluid.getFlows().size(), "岩浆未注册流动行为 ⇒ 静止，只做源");
        assertEquals(Fluids.LAVA.getFluidType(), fluid.sourceFluid(3), "派生源记住自己的流体");

        fluid.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        fluid.tick(ctx);
        assertEquals(Fluids.WATER.getFluidType(), fluid.sourceFluid(3), "同槽重注册 ⇒ 整条覆盖");
        assertEquals(9, fluid.getFlows().size(), "水恢复扩散（9 格全可达）");
    }

    @Test
    @DisplayName("⑲ 派生源跨 tick 持久；EMPTY 单例的派生源 API 是 noop")
    void generatedSource_lifecycleAndEmptyNoop() {
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        fluid.tick(ctx);
        assertTrue(fluid.isSource(2));
        assertTrue(fluid.hasGeneratedSources(), "派生源是持久资产");

        fluid.tick(ctx);
        fluid.tick(ctx);
        assertTrue(fluid.isSource(2), "多次重算后仍存活（无物可依也独立存在）");

        // EMPTY noop 安全（匿名子类必须覆写全部可变方法）
        ContainerFluidData.EMPTY.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        ContainerFluidData.EMPTY.removeGeneratedSource(0);
        assertFalse(ContainerFluidData.EMPTY.hasGeneratedSources());
        assertFalse(ContainerFluidData.EMPTY.isGeneratedSource(0));
    }

    @Test
    @DisplayName("⑳ 落盘 CODEC：只序列化派生源，往返一致（桶源 / 流动表不落）")
    void codec_roundTripsGeneratedSourcesOnly() {
        var data = new ContainerFluidData();
        data.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        data.getFlows().put(1, new ContainerFluidData.FlowEntry(1, false, 0, Fluids.WATER.getFluidType()));

        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var tag = ContainerFluidData.CODEC.encodeStart(ops, data).getOrThrow();
        var restored = ContainerFluidData.CODEC.parse(ops, tag).getOrThrow();

        assertEquals(java.util.Map.of(3, Fluids.WATER.getFluidType()), restored.getGeneratedSources(),
            "只有派生源应往返；桶源 / 流动表不落盘");
        assertTrue(restored.getFlows().isEmpty(), "流动表不落盘（下一 tick 由 BFS 重算）");
    }

    @Test
    @DisplayName("㉑ 晋升接缝：shouldPromote 为真的流动格升格为派生源（并重跑 BFS）")
    void promoteHook_promotesEligibleCell() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2; // 水规则：任意 2 邻源
            }
        });
        // 源在 slot 0 与 slot 2 ⇒ 中间的 slot 1 有 2 个源邻居 ⇒ 应晋升
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertTrue(fluid.isGeneratedSource(1), "slot 1 有 2 个源邻居(0,2) ⇒ 应升格为派生源");
        assertTrue(fluid.isSource(1), "升格后是源");
    }

    @Test
    @DisplayName("㉒ 转化接缝：transformItem 的产物写回源格（仅源格）")
    void transformHook_writesBackAtSourceCell() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public ItemStack transformItem(ItemStack item) {
                return item.is(Items.DIRT) ? new ItemStack(Items.GRASS_BLOCK) : null;
            }
        });
        // 派生源格上放非活物品 DIRT ⇒ 应被转化（非源格不转化）
        var ctx = row(new ItemStack(Items.DIRT, 1));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(Items.GRASS_BLOCK, ctx.getItem(0).getItem(), "源格上的 DIRT 应转化为草方块");
    }

    // ── 水晋升 / 最小转化（流体侧批次二 F2，2026-10-03）─────────

    /**
     * 注册「生产口径」的水行为：流动 7 + 晋升 ≥2 邻源 + 一条**催化剂型**转化
     * （DIRT→草方块，1:1 且**不消耗源**）。
     *
     * <p>⚠️ 2026-10-06 定稿：转化表不再有「空桶→水桶」条目 —— 非活化物品不得消耗活化资产，
     * 故测试也改用与桶无关的转化（桶侧行为见 ㊳ / ㉕）。</p>
     */
    private static void registerProductionWaterBehavior() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
            @Override public ItemStack transformItem(ItemStack item) {
                return item.is(Items.DIRT) && !LivingItemManager.isLivingItem(item)
                    ? new ItemStack(Items.GRASS_BLOCK, item.getCount())
                    : null;
            }
        });
    }

    // ── 蔓延时序化：慢流体（2026-10-05）────────────────────

    /**
     * 注册慢流体（岩浆 FluidType）：flowSpeed 10、maxLevel 3 + 焚毁 / 前沿反应 —— CA 渐进生长。
     * 产物对同生产口径（{@code WaterRegistration}）：**流动格 → 圆石，源格 → 黑曜石**。
     */
    private static void registerSlowLava() {
        registerSlowLava(10);
    }

    /**
     * 注册慢流体（岩浆 FluidType）：maxLevel 3 + 焚毁 / 前沿反应 —— CA 渐进生长。
     * 产物对同生产口径（{@code WaterRegistration}）：**流动格 → 圆石，源格 → 黑曜石**。
     *
     * @param flowSpeed 岩浆节拍（默认 10；生产口径是 30 —— 见 {@link #registerProductionCadence()}）
     */
    private static void registerSlowLava(int flowSpeed) {
        FluidFlowBehaviors.register(Fluids.LAVA.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return 3; }
            @Override public int flowSpeed() { return flowSpeed; }
            @Override public IncinerateResult incinerateResult(ItemStack item) {
                if (isStoneFamilyFull(item)) return IncinerateResult.SPAWN_SOURCE;
                if (item.has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT)) return IncinerateResult.SURVIVE;
                return IncinerateResult.BURN;
            }
            @Override public ItemStack frontierReaction(FluidType neighbor) {
                return neighbor == Fluids.WATER.getFluidType()
                    ? new ItemStack(Items.COBBLESTONE) : null;
            }
            @Override public ItemStack frontierSourceReaction(FluidType neighbor) {
                return neighbor == Fluids.WATER.getFluidType()
                    ? new ItemStack(Items.OBSIDIAN) : null;
            }
            // 源格替换（2026-10-06 A 档）：同生产口径，源格可被水替换
            @Override public boolean canBeReplacedBy(FluidType incoming) {
                return incoming == Fluids.WATER.getFluidType();
            }
        });
    }

    /**
     * 注册**生产节拍**：水 5t/格、岩浆 30t/格（原版 getTickDelay 派生值）。
     *
     * <p>跨流体几何的用例必须用它 —— 基线水是<b>瞬时</b>（speed 0）⇒ 到达时间恒 0 ⇒
     * 会赢走所有争用格（岩浆只剩源格）⇒ 看到的是"退化后的"几何，不是生产行为。</p>
     */
    private static void registerProductionCadence() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, 5));
        registerSlowLava(30);
    }

    private static boolean isStoneFamilyFull(ItemStack item) {
        return (item.is(Items.COBBLESTONE) || item.is(Items.STONE) || item.is(Items.DEEPSLATE))
            && item.getCount() >= item.getMaxStackSize();
    }

    @Test
    @DisplayName("㉗ 慢流体渐进生长：每 flowSpeed tick 推进一格，非一拍全淹")
    void slowFluid_gradualGrowth() {
        registerSlowLava();
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());

        fluid.tick(ctx);   // tick 1：首拍即推进一次（首环）
        assertEquals(2, fluid.getFlows().size(), "tick 1：源 + 第一环");
        assertEquals(0, fluid.getFlows().get(0).level());
        assertEquals(1, fluid.getFlows().get(1).level(), "第一环 level 1");

        fluid.tick(ctx);   // tick 2~10：未到节拍，冻结
        assertEquals(2, fluid.getFlows().size(), "未到节拍不生长");

        for (int t = 0; t < 9; t++) fluid.tick(ctx);   // 至 tick 11：第二环
        assertEquals(3, fluid.getFlows().size());
        assertEquals(2, fluid.getFlows().get(2).level(), "第二环 level 2");

        for (int t = 0; t < 10; t++) fluid.tick(ctx);   // 至 tick 21：第三环（maxLevel 收敛）
        assertEquals(4, fluid.getFlows().size(), "maxLevel 3 ⇒ 覆盖 0..3");
        assertEquals(3, fluid.getFlows().get(3).level());

        for (int t = 0; t < 15; t++) fluid.tick(ctx);   // 收敛后稳定
        assertEquals(4, fluid.getFlows().size(), "收敛后不再生长");
    }

    @Test
    @DisplayName("㉘ 慢流体移除即时：源被汲走 ⇒ 全部流动当拍清除（不等节拍）")
    void slowFluid_removalInstant() {
        registerSlowLava();
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        for (int t = 0; t < 25; t++) fluid.tick(ctx);
        assertFalse(fluid.getFlows().isEmpty(), "先确认已生长");

        fluid.removeGeneratedSource(0);
        fluid.tick(ctx);   // 下一拍：目标全空 ⇒ 修剪即时（即使未到生长节拍）
        assertTrue(fluid.getFlows().isEmpty(), "移除即时，不渐进干涸");
    }

    @Test
    @DisplayName("㉙ 慢流体挤没即时：活物品压进流网 ⇒ 受影响格当拍清除")
    void slowFluid_squeezeInstant() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        h.slots[0] = livingNonWater();   // 活物品压住源 ⇒ 源挤没
        fluid.tick(ctx);
        assertEquals(0, fluid.getFlows().size(), "源被挤没 ⇒ 整网目标消失 ⇒ 当拍全清");
    }

    @Test
    @DisplayName("㉚ 水按派生节拍渐进（tickDelay 5）：一拍只长一格，非秒淹")
    void water_derivedCadenceGradual() {
        // 水行为不覆写 flowSpeed ⇒ 派生自原版 getTickDelay = 5（null level 兜底同值）
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            new FluidFlowBehavior() {
                @Override public boolean canFlow() { return true; }
                @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            });
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());

        fluid.tick(ctx);
        assertEquals(2, fluid.getFlows().size(), "tick 1：源 + 第一环（level 1）");

        fluid.tick(ctx);
        assertEquals(2, fluid.getFlows().size(), "tick 2：未到节拍");

        for (int t = 0; t < 4; t++) fluid.tick(ctx);   // 至 tick 6：第二环
        assertEquals(3, fluid.getFlows().size(), "tick 6：level 2 环推进");

        for (int t = 0; t < 30; t++) fluid.tick(ctx);   // 充分推进
        assertEquals(8, fluid.getFlows().size(), "充分时间后收敛到全距（slot 0..7）");
    }

    // ── 活熔岩：刷石机 + 焚毁/源诞生（2026-10-06）────────────

    @Test
    @DisplayName("㉝ 刷石机：熔岩前沿遇水凝固为圆石（frontierReaction），产物格不是墙 ⇒ 圆石持续累加")
    void frontierReaction_cobblestoneAtContact() {
        // 生产节拍 + **够远的几何**（9 格单行两端）：岩浆 30t/格、水 5t/格 ⇒
        // 岩浆守得住紧贴自己的一格（30 < 5×7），刷石机才成立（见 living-fluid-tech.md §3.1 §4）
        registerProductionCadence();
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(8, Fluids.WATER.getFluidType());

        for (int t = 0; t < 120; t++) fluid.tick(ctx);

        assertTrue(fluid.isSource(0), "岩浆源存活（几何够远 ⇒ 水不会贴到源格）");
        assertTrue(fluid.isSource(8), "水源存活");
        assertTrue(ctx.getItem(1).is(Items.COBBLESTONE),
            "圆石落在**熔岩侧**接触格（slot 1 = 岩浆守住的那一格），水格不动");
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(2).fluid(),
            "水占满它在时间上先到的格子（slot 2 起）—— 不再有「在水的流域内却进不去」的墙");

        int before = ctx.getItem(1).getCount();
        for (int t = 0; t < 120; t++) fluid.tick(ctx);   // 再跑几轮岩浆节拍
        assertTrue(ctx.getItem(1).getCount() > before,
            "产物格不是墙：岩浆重新流入同一格再反应 ⇒ 圆石累加（刷石机自动产出，无需玩家挖）");
    }

    @Test
    @DisplayName("晋升只数**同流体**邻源：流动水夹在两个岩浆源之间 ⇒ 不晋升（2026-10-07 收尾修复）")
    void promotion_countsOnlySameFluidSourceNeighbors() {
        // 岩浆用**静止档**：只占自己的源格 0 / 2，不扩张 ⇒ slot 1 留给水
        FluidFlowBehaviors.register(Fluids.LAVA.getFluidType(), FluidFlowBehavior.STATIC);
        // 水：maxLevel 7 + 「≥2 邻源即晋升」的生产口径
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
        });

        // 27 格（9×3）：岩浆源占 0 / 2（单行会把 slot 1 封死——源格不可穿越），
        // 水从下一行绕上来（13 → 10 → 1）⇒ slot 1 才成为「夹在两个岩浆源之间的流动水」
        FakeHandler h = new FakeHandler(27);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(2, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(13, Fluids.WATER.getFluidType());

        boolean slot1WasWater = false;
        for (int t = 0; t < 30; t++) {
            fluid.tick(ctx);
            var at1 = fluid.getFlows().get(1);
            slot1WasWater |= at1 != null && at1.fluid() == Fluids.WATER.getFluidType();
        }

        assertTrue(slot1WasWater, "前置：slot 1 曾是流动水（夹在两个岩浆源之间）");
        assertTrue(fluid.isSource(0) && fluid.isSource(2), "前置：两侧都是岩浆源");
        assertFalse(fluid.isGeneratedSource(1),
            "岩浆源**不算**水的邻源 ⇒ 不满足「同流体 ≥2 邻源」⇒ 不晋升（修复前会错误升成水源）");
    }

    @Test
    @DisplayName("到达时间抢占：同一格两流体竞争，**到得慢的让位**（水 5t/格 胜 岩浆 30t/格）")
    void arrivalTime_slowerFluidYields() {
        registerProductionCadence();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        // 岩浆源 0、水源 8 ⇒ 中间格双方都够得着，按到达时间判给速度快的水
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(8, Fluids.WATER.getFluidType());

        boolean slot1EverWater = false;
        for (int t = 0; t < 120; t++) {
            fluid.tick(ctx);
            var at1 = fluid.getFlows().get(1);
            slot1EverWater |= at1 != null && at1.fluid() == Fluids.WATER.getFluidType();
        }

        assertTrue(fluid.isSource(0), "岩浆源存活（几何够远）");
        assertFalse(slot1EverWater,
            "slot 1：岩浆 30t < 水 35t ⇒ **岩浆守得住**，水从没进去过（它不是让位的一方）");
        for (int slot = 3; slot <= 7; slot++) {
            var at = fluid.getFlows().get(slot);
            assertTrue(at != null && at.fluid() == Fluids.WATER.getFluidType(),
                "slot " + slot + "：水 5t/格 远快于岩浆 30t/格 ⇒ 归水（慢者让位）");
        }
    }

    @Test
    @DisplayName("㊿⁺ 几何约束（到达时间分配的代价）：两源太近 ⇒ 水贴到岩浆源 ⇒ 黑曜石 + 源湮灭")
    void tooCloseGeometry_lavaSourcePetrifies() {
        registerProductionCadence();
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(4, Fluids.WATER.getFluidType());   // 仅 4 格 ⇒ 岩浆守不住前沿

        for (int t = 0; t < 120; t++) fluid.tick(ctx);

        assertEquals(Items.OBSIDIAN, ctx.getItem(0).getItem(),
            "水在时间上先到 ⇒ 贴到岩浆源 ⇒ 源格反应 ⇒ 黑曜石（原版同款：水够得着岩浆源就报废）");
        assertFalse(fluid.isGeneratedSource(0), "岩浆源被反应湮灭");
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(1).fluid(),
            "岩浆领地收缩为 0 ⇒ 水接管（刷石机在内的近距离玩法不成立，需把两源摆远）");
    }

    @Test
    @DisplayName("㉞ 焚毁→源诞生：熔岩流动格上的满组石头系物品 ⇒ 该格诞生活熔岩源（新配方）")
    void incinerate_fullStoneStack_spawnsLavaSource() {
        registerSlowLava();   // 岩浆 flowing(3, 10) + 焚毁/前沿反应
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        h.slots[1] = new ItemStack(Items.STONE, 64);

        fluid.tick(ctx);

        assertTrue(fluid.isGeneratedSource(1), "满组石头喂养 ⇒ 该格诞生活熔岩源");
        assertTrue(ctx.getItem(1).isEmpty(), "石头被消耗");
        assertTrue(fluid.isSource(1), "新生源立即可用");
    }

    @Test
    @DisplayName("㉟ 部分石头堆焚毁：不满足满组 ⇒ 焚毁不生源")
    void incinerate_partialStoneStack_burns() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        h.slots[1] = new ItemStack(Items.STONE, 16);

        fluid.tick(ctx);

        assertTrue(ctx.getItem(1).isEmpty(), "部分石头堆焚毁");
        assertFalse(fluid.isGeneratedSource(1), "不满组 ⇒ 不生源（slot 0 是测试自带的源，不能查 hasGeneratedSources）");
    }

    @Test
    @DisplayName("㊱ 防火物品存活：FIRE_RESISTANT 组件物品在熔岩格共存不焚毁")
    void incinerate_fireResistant_survives() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        // 下界合金锭 = 原版 fireResistant（DataComponents.FIRE_RESISTANT）物品
        // ⚠️ 附魔金苹果不防火（原版只有 rarity/food/glint 组件）——别拿它当防火样本
        h.slots[1] = new ItemStack(Items.NETHERITE_INGOT);

        fluid.tick(ctx);

        assertEquals(Items.NETHERITE_INGOT, ctx.getItem(1).getItem(), "防火物品存活");
    }

    @Test
    @DisplayName("㊲ 源格也焚毁：满组石头放进**已存在的**熔岩源 ⇒ 只消耗石头，源保留（不重复诞生）")
    void incinerate_fullStoneStackAtSource_consumesOnly() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        h.slots[0] = new ItemStack(Items.STONE, 64);

        fluid.tick(ctx);

        assertTrue(ctx.getItem(0).isEmpty(), "源格同样焚毁：满组石头被消耗");
        assertTrue(fluid.isSource(0), "源仍在（源已存在 ⇒ 不再诞生新源）");
        assertEquals(1, fluid.getGeneratedSources().size(), "没有多出第二个源");
    }

    @Test
    @DisplayName("㊳ 岩浆不转化：非活空桶放进熔岩源格 ⇒ 被焚毁（不是变岩浆桶 —— 转化是活水源的机制三）")
    void lavaSource_incineratesBucket_notTransform() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        h.slots[0] = new ItemStack(Items.BUCKET);

        fluid.tick(ctx);

        assertTrue(ctx.getItem(0).isEmpty(), "空桶被焚毁（岩浆没有转化条目）");
        assertTrue(fluid.isSource(0), "焚毁只销毁物品，不动容器级资产（源保留）");
    }

    @Test
    @DisplayName("㊴ 黑曜石①：岩浆**源格**邻水 ⇒ 产物黑曜石 + 源湮灭（源/流动产物分开）")
    void frontierSourceReaction_obsidianAtSource() {
        registerSlowLava();
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(1, Fluids.WATER.getFluidType());

        fluid.tick(ctx);

        assertEquals(Items.OBSIDIAN, ctx.getItem(0).getItem(), "源格遇水 ⇒ 黑曜石（不是圆石）");
        assertFalse(fluid.isGeneratedSource(0), "源被反应湮灭");
        assertFalse(fluid.isSource(0), "该格不再是源");
    }

    @Test
    @DisplayName("㊵ 黑曜石②：黑曜石被蔓延回来的岩浆焚毁（∉ 石头系、不防火 ⇒ BURN）")
    void obsidianInLavaCell_burns() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        h.slots[1] = new ItemStack(Items.OBSIDIAN);

        fluid.tick(ctx);

        assertTrue(ctx.getItem(1).isEmpty(), "黑曜石在岩浆流动格被焚毁 ⇒ 循环回到圆石阶段");
    }

    @Test
    @DisplayName("㊶ 黑曜石③：端到端循环 —— 满组圆石 ⇒ 岩浆源 ⇒ 黑曜石 ⇒ 焚毁 ⇒ 回到圆石")
    void obsidianLoop_endToEnd() {
        registerProductionCadence();   // 生产节拍 + 够远几何（同 ㉝）：岩浆守得住 slot 1
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(8, Fluids.WATER.getFluidType());

        // 先跑到稳态：岩浆守住 slot 1、水占满 slot 2 起、圆石在 slot 1 累加
        for (int t = 0; t < 120; t++) fluid.tick(ctx);
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(2).fluid(),
            "前置：水已推进到 slot 2（与岩浆接触格相邻）");

        h.slots[1] = new ItemStack(Items.COBBLESTONE, 64);   // 接触格攒满 ⇒ 焚毁走 SPAWN_SOURCE

        boolean sawSource = false, sawObsidian = false;
        for (int t = 0; t < 200 && !sawObsidian; t++) {
            fluid.tick(ctx);
            if (fluid.isGeneratedSource(1)) sawSource = true;
            sawObsidian = ctx.getItem(1).is(Items.OBSIDIAN);
        }
        assertTrue(sawSource, "满组圆石（石头系）⇒ 该格诞生活熔岩源（循环的驱动步）");
        assertTrue(sawObsidian, "新生源邻水 ⇒ 黑曜石");
        assertFalse(fluid.isGeneratedSource(1), "源格反应 ⇒ 源湮灭");

        boolean backToCobble = false;
        for (int t = 0; t < 200 && !backToCobble; t++) {
            fluid.tick(ctx);
            backToCobble = ctx.getItem(1).is(Items.COBBLESTONE);
        }
        assertTrue(backToCobble, "黑曜石被蔓延回来的岩浆焚毁 ⇒ 重新反应 ⇒ 回到圆石（循环闭合）");
    }

    @Test
    @DisplayName("㊻ 源格替换（对齐原版）：活水桶倒进**活熔岩源格** ⇒ 该格变成水源，原岩浆及下游退走")
    void pour_overwritesLavaSourceWithWater() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        for (int t = 0; t < 40; t++) fluid.tick(ctx);          // 先让岩浆流开（30t/格）
        assertEquals(Fluids.LAVA.getFluidType(), fluid.sourceFluid(0), "前置：slot 0 是活熔岩源");
        assertNotNull(fluid.getFlows().get(1), "前置：岩浆已蔓延到 slot 1");

        // 倒水：pour 的「异种源格 + 允许替换」分支 ⇒ 同键覆盖源类型
        assertTrue(LivingBucketInteractSupport.replacesResidentSource(
            fluid.sourceFluid(0), Fluids.WATER.getFluidType()), "熔岩源允许被水替换（0.444 门槛的 2D 投影）");
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(Fluids.WATER.getFluidType(), fluid.sourceFluid(0), "该格已是水源（原岩浆源被覆盖湮灭）");
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(0).fluid(), "实际层当拍改写为水");
        var down = fluid.getFlows().get(1);
        assertTrue(down == null || down.fluid() != Fluids.LAVA.getFluidType(),
            "失去供给的岩浆下游退走（目标层收敛 + 移除即时）");
    }

    @Test
    @DisplayName("㊼ 源格替换·反向：活岩浆桶倒进**活水源格** ⇒ 该格变成岩浆源（原版同样替换）")
    void pour_overwritesWaterSourceWithLava() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        for (int t = 0; t < 12; t++) fluid.tick(ctx);          // 先让水蔓延开（5t/格）
        assertEquals(Fluids.WATER.getFluidType(), fluid.sourceFluid(0), "前置：slot 0 是活水源");
        assertNotNull(fluid.getFlows().get(1), "前置：水已蔓延到 slot 1");

        // 对称性：默认注册表里**水行为不覆写** canBeReplacedBy（registerSlowLava 只覆盖岩浆），
        // 生产口径的覆写在 WaterRegistration —— 这里显式注册水的生产口径覆写。
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public boolean canBeReplacedBy(FluidType incoming) {
                return incoming == Fluids.LAVA.getFluidType();
            }
        });
        assertTrue(LivingBucketInteractSupport.replacesResidentSource(
            fluid.sourceFluid(0), Fluids.LAVA.getFluidType()), "水源允许被岩浆替换（对称）");
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.tick(ctx);

        assertEquals(Fluids.LAVA.getFluidType(), fluid.sourceFluid(0), "该格已是活熔岩源");
        assertEquals(Fluids.LAVA.getFluidType(), fluid.getFlows().get(0).fluid(), "实际层当拍改写为岩浆");
        var down = fluid.getFlows().get(1);
        assertTrue(down == null || down.fluid() != Fluids.WATER.getFluidType(),
            "失去供给的水下游退走（不会两种流体共存）");
    }

    @Test
    @DisplayName("㊾ 不驱逐：水源流到 4格 时在**前方空槽**倒岩浆源 ⇒ 水原地不动、无空档，接触面凝固圆石")
    void lavaPourAheadOfWaterFlow_doesNotDisplace() {
        registerSlowLava();                       // 岩浆 flowSpeed 10、maxLevel 3 + 前沿反应
        // 水限maxLevel 4 ⇒ 水只能到 slot 4，slot 5/6 保持空白（基线水是瞬时且上限 7，一拍就铺满）
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), FluidFlowBehavior.flowing(4, 0));
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(4).fluid(), "前置：slot 4 是流动水");
        assertTrue(fluid.getFlows().get(5) == null && fluid.getFlows().get(6) == null,
            "前置：slot 5/6 还是空的（水没流到）");

        // 在 slot 6 倒岩浆（与水隔一格 ⇒ 旧目标层会按距离抢 slot 4/5，把水驱逐掉）
        fluid.registerGeneratedSource(6, Fluids.LAVA.getFluidType());
        fluid.tick(ctx);

        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(4).fluid(),
            "核心：异种流体实际占据的格不被抢占 ⇒ 水不消失（旧行为：这一拍就被 pruneActual 删掉）");

        // 岩浆推进到接触面（slot 5）⇒ 机制五流动格分支 ⇒ 圆石落格并累加（刷石机启动，节奏不变）
        boolean cobble = false;
        boolean waterHeldSlot4 = true;
        for (int t = 0; t < 40 && !cobble; t++) {
            fluid.tick(ctx);
            var at4 = fluid.getFlows().get(4);
            waterHeldSlot4 &= at4 != null && at4.fluid() == Fluids.WATER.getFluidType();
            cobble = ctx.getItem(5).is(Items.COBBLESTONE);
        }
        assertTrue(waterHeldSlot4, "水全程没被驱逐（旧行为：倒完立刻空一格，约 1.5s 后才变圆石）");
        assertTrue(cobble, "岩浆前沿停在接触面并凝固成圆石（原版节奏：到达即反应）");
    }

    @Test
    @DisplayName("㊿ 倒进**异种流动格** ⇒ 直接覆盖：活岩浆桶倒进流动水格 ⇒ 该格成岩浆源 → 源格遇水 ⇒ 黑曜石 + 源湮灭")
    void lavaPourIntoFlowingWaterCell_overwritesAndPetrifies() {
        registerSlowLava();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        for (int t = 0; t < 6; t++) fluid.tick(ctx);    // 水流到 slot 6
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(6).fluid(), "前置：slot 6 是流动水");

        // 倒桶是玩家显式行为 ⇒ 覆盖该格（B 档口径：源播种仍然覆盖，只禁「蔓延抢占」）
        fluid.registerGeneratedSource(6, Fluids.LAVA.getFluidType());
        fluid.tick(ctx);

        assertEquals(Items.OBSIDIAN, ctx.getItem(6).getItem(),
            "该格成岩浆源且紧邻水 ⇒ 源格反应当拍出黑曜石（不是圆石）");
        assertFalse(fluid.isGeneratedSource(6), "源格反应 ⇒ 岩浆源湮灭（不会反复重生）");
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(5).fluid(),
            "旁边的水**不受影响**（新守卫：蔓延不抢占异种格）");

        fluid.tick(ctx);
        assertEquals(Fluids.WATER.getFluidType(), fluid.getFlows().get(6).fluid(),
            "水随后灌回该格（黑曜石是非活物品，不阻挡水流）");
    }

    @Test
    @DisplayName("㊷ 契约默认回退：只覆写 frontierReaction 的流体，其源格产物也走 frontierReaction")
    void frontierSourceReaction_defaultFallback() {
        FluidFlowBehaviors.register(Fluids.LAVA.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return 3; }
            @Override public int flowSpeed() { return 0; }
            @Override public ItemStack frontierReaction(FluidType neighbor) {
                return neighbor == Fluids.WATER.getFluidType() ? new ItemStack(Items.COBBLESTONE) : null;
            }
        });
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());
        fluid.registerGeneratedSource(1, Fluids.WATER.getFluidType());

        fluid.tick(ctx);

        assertEquals(Items.COBBLESTONE, ctx.getItem(0).getItem(),
            "default 回退 ⇒ 不区分源/流动的流体行为零变化（源格也是圆石）");
    }

    // ── 统一时钟（2026-10-06）：生长类逻辑一律走该流体自己的节拍 ──────────

    @Test
    @DisplayName("㊸ 时钟①：晋升只在该流体的**推进拍**发生（水 flowSpeed=5 ⇒ 汲走后第 5 个推进拍才补回）")
    void promotion_followsFluidClock() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 5; }   // 与生产水同节拍
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
        });
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        for (int t = 0; t < 6; t++) fluid.tick(ctx);
        assertTrue(fluid.isGeneratedSource(1), "首个推进拍即完成晋升（水真的流到那格才升源）");

        fluid.removeGeneratedSource(1);   // 汲走中间源
        for (int t = 0; t < 4; t++) {
            fluid.tick(ctx);
            assertFalse(fluid.isGeneratedSource(1), "未到推进拍 ⇒ 不补回（第 " + (t + 1) + " 拍）");
        }
        fluid.tick(ctx);
        assertTrue(fluid.isGeneratedSource(1), "下一个推进拍 ⇒ 升格补回");
        assertTrue(fluid.isSource(1), "补回后当拍可用（源即时）");
    }

    @Test
    @DisplayName("㊹ 时钟② 等价性：晋升只是变慢，**终态与瞬时基线相同**（慢流体跑够拍数 ≡ 瞬时）")
    void promotion_slowButSameSteadyState() {
        String instant = trioSteadyState(0, 12);
        String slow = trioSteadyState(5, 60);
        assertEquals(instant, slow, "晋升上时钟只改时序，不改终态（源集合 + 流动覆盖）");
    }

    /** 三连源场景跑 N 拍，返回「源集合 | 流动覆盖」终态签名（供等价性对比）。 */
    private static String trioSteadyState(int flowSpeed, int ticks) {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return flowSpeed; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
        });
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        for (int t = 0; t < ticks; t++) fluid.tick(ctx);
        return new java.util.TreeSet<>(fluid.getGeneratedSources().keySet())
            + " | " + new java.util.TreeSet<>(fluid.getFlows().keySet());
    }

    @Test
    @DisplayName("㊺ 时钟③：物品推动按流体节拍（慢岩浆 flowSpeed=10 ⇒ 只在推进拍推，不做 4t 一次）")
    void pushItems_followsFluidClock() {
        registerSlowLava();   // flowSpeed 10
        var ctx = row(ItemStack.EMPTY, new ItemStack(Items.NETHERITE_INGOT));  // 防火物品：不被焚毁，只会被推
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.LAVA.getFluidType());

        for (int t = 0; t < 10; t++) fluid.tick(ctx);
        assertEquals(1, ctx.getItem(1).getCount(), "未到推进拍 ⇒ 物品不动（旧实现会每 4t 推一次）");

        fluid.tick(ctx);   // 下一个推进拍：岩浆长出下一格 ⇒ 同拍推动
        assertTrue(ctx.getItem(1).isEmpty(), "推进拍上物品被推走");
        assertEquals(Items.NETHERITE_INGOT, ctx.getItem(2).getItem(), "推到下游 slot 2");
    }

    @Test
    @DisplayName("㉓ 相邻两源不繁殖：slot 2 只有 1 个源邻居 ⇒ 不晋升（原版口径）")
    void adjacentSources_doNotPromote() {
        registerProductionWaterBehavior();
        var ctx = row();
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(1, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(java.util.Set.of(0, 1), fluid.getGeneratedSources().keySet(),
            "相邻两源不产生新源");
        assertFalse(fluid.isGeneratedSource(2), "slot 2 仅 1 个源邻居 ⇒ 不晋升");
    }

    @Test
    @DisplayName("㉔ 挤没自愈：夹缝源被活物品挤没后，物品移走且邻域仍 ≥2 源 ⇒ 重新派生")
    void squeezedSource_selfHealsViaPromotion() {
        registerProductionWaterBehavior();
        // 源 0、2 + 夹缝 1 先晋升出第三个源
        var fluid = new ContainerFluidData();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        fluid.tick(ctx);
        assertTrue(fluid.isGeneratedSource(1), "先晋升出夹缝源");

        h.slots[1] = livingNonWater();   // 活物品压进夹缝源
        fluid.tick(ctx);
        assertFalse(fluid.isGeneratedSource(1), "挤没");

        h.slots[1] = ItemStack.EMPTY;    // 物品移走，邻域 0、2 仍是源
        fluid.tick(ctx);
        assertTrue(fluid.isGeneratedSource(1), "邻域 ≥2 源 ⇒ 自愈重派生");
    }

    @Test
    @DisplayName("㉕ 非活化不取活化资产：非活空桶放**水源格** ⇒ 不转化、源不消耗（2026-10-06 定稿）")
    void nonLivingBucketInWaterSource_untouched() {
        registerProductionWaterBehavior();
        var ctx = row(new ItemStack(Items.BUCKET));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(Items.BUCKET, ctx.getItem(0).getItem(), "空桶不会被水源泡成水桶（转化表无桶条目）");
        assertTrue(fluid.isSource(0), "源不被非活化物品消耗（对称性：活化影响非活化，反之不许）");
    }

    @Test
    @DisplayName("㉛ 催化剂语义：转化**不消耗源**（DIRT→草方块整槽替换后源仍在）")
    void transform_neverConsumesSource() {
        registerProductionWaterBehavior();
        var ctx = row(new ItemStack(Items.DIRT, 64));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(Items.GRASS_BLOCK, ctx.getItem(0).getItem(), "整槽等量替换");
        assertEquals(64, ctx.getItem(0).getCount());
        assertTrue(fluid.isGeneratedSource(0), "催化剂语义：源保留（转化永不消耗活化资产）");
        assertTrue(fluid.isSource(0));
    }

    @Test
    @DisplayName("㉜ 晋升再生：三连源中间源被**汲走**（活空桶，活化侧操作）⇒ 下一拍补回")
    void trioSource_regeneratesAfterScoop() {
        registerProductionWaterBehavior();
        FakeHandler h = new FakeHandler(9);
        var ctx = new SimpleContainerContext(h);
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(1, Fluids.WATER.getFluidType());
        fluid.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        fluid.tick(ctx);
        assertTrue(fluid.isGeneratedSource(1), "先确认三连源就位");

        fluid.removeGeneratedSource(1);   // 汲走的服务端语义（活空桶 GUI 汲）
        fluid.tick(ctx);

        assertTrue(fluid.isGeneratedSource(1), "邻域 ≥2 源 ⇒ 晋升再生（下一拍补回）");
        assertTrue(fluid.isSource(1), "再生源当拍可用");
    }

    // ⚠️ 原「㉖ 缩容等待」已从本类移除：缩容是**转化表**的规则（{@code FluidTransformTable}），
    // 引擎的 transformItem 接缝不重复实现 —— 其守卫见 FluidTransformTableTest。

    @Test
    @DisplayName("㉗ 玩家落盘 CODEC（B.5 第三项）：容器键 → 流体数据映射往返一致")
    void playerKeyedCodec_roundTrips() {
        var inv = new ContainerFluidData();
        inv.registerGeneratedSource(2, Fluids.WATER.getFluidType());
        var ender = new ContainerFluidData();
        ender.registerGeneratedSource(5, Fluids.LAVA.getFluidType());

        var map = new java.util.HashMap<String, ContainerFluidData>();
        map.put("player_abc", inv);
        map.put("player_abc_ender_chest", ender);

        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var tag = ContainerFluidData.KEYED_CODEC.encodeStart(ops, map).getOrThrow();
        var restored = ContainerFluidData.KEYED_CODEC.parse(ops, tag).getOrThrow();

        assertEquals(2, restored.size(), "背包 + 末影箱两个键都应往返");
        assertEquals(java.util.Map.of(2, Fluids.WATER.getFluidType()),
            restored.get("player_abc").getGeneratedSources());
        assertEquals(java.util.Map.of(5, Fluids.LAVA.getFluidType()),
            restored.get("player_abc_ender_chest").getGeneratedSources());
    }
}
