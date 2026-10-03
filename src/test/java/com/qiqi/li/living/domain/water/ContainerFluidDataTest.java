package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var ctx = row(livingWaterBucket());
        assertEquals(9, ctx.getWidth(), "9 格应为 1×9 单行");

        var fluid = new ContainerFluidData();
        fluid.registerSource(0);
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
        var ctx = row(livingWaterBucket(), ItemStack.EMPTY, livingNonWater());
        var fluid = new ContainerFluidData();
        fluid.registerSource(0);
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
        var ctx = row(livingWaterBucket(), new ItemStack(Items.REDSTONE, 3));
        var fluid = new ContainerFluidData();
        fluid.registerSource(0);
        fluid.tick(ctx);

        var flows = fluid.getFlows();
        assertEquals(8, flows.size(), "非活物品不阻挡 ⇒ 仍扩散到 slot 7");
        assertEquals(1, flows.get(1).level());
    }

    @Test
    @DisplayName("④ 源移除后（槽位无活水桶）流动整体消失")
    void removedSource_clearsFlows() {
        var ctx = row(livingWaterBucket());
        var fluid = new ContainerFluidData();
        fluid.registerSource(0);
        fluid.tick(ctx);
        assertFalse(fluid.getFlows().isEmpty(), "先确认有水");

        fluid.removeSource(0);
        fluid.tick(ctx);
        assertTrue(fluid.getFlows().isEmpty(), "源移除且槽位无桶 ⇒ 无播种 ⇒ 全空");
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
        var ctx = row(livingWaterBucket(), new ItemStack(Items.REDSTONE, 1));
        var fluid = new ContainerFluidData();
        fluid.registerSource(0);

        for (int t = 0; t < 4; t++) fluid.tick(ctx);

        assertTrue(ctx.getItem(1).isEmpty(), "slot 1 的物品应被推走");
        assertEquals(Items.REDSTONE, ctx.getItem(2).getItem(), "物品应到下游 slot 2");
        assertEquals(1, ctx.getItem(2).getCount());
    }

    @Test
    @DisplayName("⑦ 行为接缝：会流动流体按各自 maxLevel 扩散（改 maxLevel=3 ⇒ 只到 slot 3）")
    void behaviorSeam_respectsMaxLevel() {
        registerWater(3);
        var ctx = row(livingWaterBucket());
        var fluid = new ContainerFluidData();
        fluid.registerSource(0);
        fluid.tick(ctx);

        assertEquals(4, fluid.getFlows().size(), "maxLevel=3 ⇒ 覆盖 slot 0..3");
        assertFalse(fluid.getFlows().containsKey(4), "slot 4 超过 maxLevel=3");
    }

    @Test
    @DisplayName("⑧ 行为接缝：静止流体只做源、不扩散")
    void behaviorSeam_staticDoesNotSpread() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), FluidFlowBehavior.STATIC);
        var ctx = row(livingWaterBucket());
        var fluid = new ContainerFluidData();
        fluid.registerSource(0);
        fluid.tick(ctx);

        assertEquals(1, fluid.getFlows().size(), "静止 ⇒ 只有源自己");
        assertTrue(fluid.getFlows().get(0).isSource());
    }

    @Test
    @DisplayName("⑨ 集成回归：TickContext 构造时创建容器流体数据（1a-4 漏建 ⇒ 水流失效）")
    void tickContext_createsFluidData() {
        var ctx = row(livingWaterBucket());
        var tick = new TickContext(ctx);
        assertNotSame(ContainerFluidData.EMPTY, tick.fluidData(),
            "1a-4 曾漏掉创建 ⇒ tick.fluidData() 恒 EMPTY ⇒ 桶 registerSource 被跳过 ⇒ 水流失效");
    }
}
