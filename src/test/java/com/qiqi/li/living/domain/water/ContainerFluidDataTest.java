package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerDataKeys;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
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
    @DisplayName("⑥ 水流推动：第 4 tick 沿水流方向把非活物品推下游")
    void pushItems_movesItemDownstreamOn4thTick() {
        var ctx = row(ItemStack.EMPTY, new ItemStack(Items.REDSTONE, 1));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());

        for (int t = 0; t < 4; t++) fluid.tick(ctx);

        assertTrue(ctx.getItem(1).isEmpty(), "slot 1 的物品应被推走");
        assertEquals(Items.REDSTONE, ctx.getItem(2).getItem(), "物品应到下游 slot 2");
        assertEquals(1, ctx.getItem(2).getCount());
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
    @DisplayName("⑨ 集成回归：TickContext 构造时创建容器流体数据（1a-4 漏建 ⇒ 水流失效）")
    void tickContext_createsFluidData() {
        var ctx = row();
        var tick = new TickContext(ctx);
        assertNotSame(ContainerFluidData.EMPTY, tick.fluidData(),
            "1a-4 曾漏掉创建 ⇒ tick.fluidData() 恒 EMPTY ⇒ 流体数据无处着落 ⇒ 水流失效");
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
        var fluid = ctx.peekContainerData(ContainerDataKeys.FLUID);
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

    /** 注册「生产口径」的水行为：流动 7 + 晋升 ≥2 邻源 + 空桶转化（同 WaterRegistration）。 */
    private static void registerProductionWaterBehavior() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
            @Override public ItemStack transformItem(ItemStack item) {
                if (item.is(Items.BUCKET) && item.getCount() == 1
                        && !LivingItemManager.isLivingItem(item)) {
                    return new ItemStack(Items.WATER_BUCKET);
                }
                return null;
            }
        });
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
    @DisplayName("㉕ 最小转化：源格上的单个空桶 → 水桶（非活，与源共存）")
    void transform_minimalBucketToWaterBucket() {
        registerProductionWaterBehavior();
        var ctx = row(new ItemStack(Items.BUCKET));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(Items.WATER_BUCKET, ctx.getItem(0).getItem(), "空桶被源浸泡成水桶");
        assertTrue(fluid.isSource(0), "源不受转化影响");
    }

    @Test
    @DisplayName("㉖ 缩容等待：空桶堆叠 >1 不转化（水桶最大堆叠 1，整槽无法等量替换）")
    void transform_shrinkingStackStalls() {
        registerProductionWaterBehavior();
        var ctx = row(new ItemStack(Items.BUCKET, 16));
        var fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(0, Fluids.WATER.getFluidType());
        fluid.tick(ctx);

        assertEquals(Items.BUCKET, ctx.getItem(0).getItem(), "16 桶不转化，等待玩家拆分");
        assertEquals(16, ctx.getItem(0).getCount());
    }

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
