package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerDataKeys;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.SimpleContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 容器流体**端到端**测试 —— 走真实 {@code ContainerLivingItemHandler.processContext}。
 *
 * <p><b>为什么需要</b>：{@code ContainerFluidDataTest} 是**单元级**（直接调引擎/驱动），
 * 证明不了「在真实 tick 流程里，桶注册源 + 驱动跑 BFS」这条链真的接通。
 * 本类补这一层：① 覆盖 1a-4 回归（流体数据创建）的**端到端版**；② 覆盖 {@code LivingFluidFunction}
 * 自维持驱动在真实分组/排序下确实生效。</p>
 *
 * <p>容器约定：9 格 handler ⇒ 宽度推断 9 ⇒ <b>1×9 单行</b>。</p>
 */
class ContainerFluidIntegrationTest {

    /** 模拟真实 IItemHandler 语义（可插入/可提取）。 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(ItemStack... slots) { this.slots = slots; }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ItemStack existing = getStackInSlot(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) return stack;
            int space = Math.min(getSlotLimit(slot), stack.getMaxStackSize()) - existing.getCount();
            int inserted = Math.min(space, stack.getCount());
            if (inserted <= 0) return stack;
            ItemStack remaining = stack.copy();
            remaining.shrink(inserted);
            if (!simulate) {
                slots[slot] = existing.isEmpty() ? stack.copyWithCount(inserted)
                    : existing.copyWithCount(existing.getCount() + inserted);
            }
            return remaining;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = getStackInSlot(slot);
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int taken = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(taken);
            if (!simulate) existing.setCount(existing.getCount() - taken);
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

    /** 服务端 Level mock（带递增世界时钟）。 */
    private static Level mockServerLevel() {
        Level level = org.mockito.Mockito.mock(Level.class);
        org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
        long[] tick = {0};
        org.mockito.Mockito.when(level.getGameTime()).thenAnswer(inv -> tick[0]++);
        return level;
    }

    /** 测试自足：基线注册水为「会流动，上限 7」（同 WaterRegistration）。 */
    @BeforeEach
    void baselineWaterBehavior() {
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, 0));
    }

    @Test
    @DisplayName("端到端：processContext 驱动水流（预置派生源 + 驱动跑 BFS）")
    void processContext_drivesWaterFlow() {
        ItemStack[] slots = new ItemStack[9];
        Level level = mockServerLevel();
        var ctx = new SimpleContainerContext(new FakeHandler(slots), level);

        // 桶源退役（2026-10-03）：源由倒水/落盘而来，预置派生源验证端到端 BFS
        ContainerLivingItemHandler.getFluidData(ctx)
            .registerGeneratedSource(0, Fluids.WATER.getFluidType());
        ContainerLivingItemHandler.processContext(ctx, level);

        var fluid = ctx.peekContainerData(ContainerDataKeys.FLUID);
        assertNotNull(fluid, "流体数据应已创建（1a-4 回归的端到端守卫）");
        assertEquals(8, fluid.getFlows().size(), "应完成 BFS（slot 0..7）");
        assertTrue(fluid.getFlows().get(0).isSource(), "slot 0 应为源（桶在真实流程里注册）");
        assertEquals(1, fluid.getFlows().get(1).level(), "slot 1 应为 level 1");
    }

    @Test
    @DisplayName("端到端：源清空后 BE 附件写回 EMPTY（跨存档残留守卫，2026-10-04）")
    void writeback_clearsBeAttachmentWhenFluidEmpties() {
        Level level = mockServerLevel();
        BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(be.getBlockPos()).thenReturn(BlockPos.ZERO);

        var ctx = new SimpleContainerContext(new FakeHandler(new ItemStack[9]),
            java.util.List.of(BlockPos.ZERO), java.util.List.of(be));
        ContainerLivingItemHandler.getFluidData(ctx)
            .registerGeneratedSource(0, Fluids.WATER.getFluidType());

        var captor = org.mockito.ArgumentCaptor.forClass(ContainerFluidData.class);
        ContainerLivingItemHandler.processContext(ctx, level);
        org.mockito.Mockito.verify(be, org.mockito.Mockito.atLeastOnce())
            .setData(org.mockito.Mockito.eq(LivingComponents.CONTAINER_FLUID_DATA.value()), captor.capture());
        assertFalse(captor.getValue().isEmpty(), "有源 ⇒ 附件写回非空（源落盘）");

        ContainerLivingItemHandler.getFluidData(ctx).removeGeneratedSource(0);
        ContainerLivingItemHandler.processContext(ctx, level);
        org.mockito.Mockito.verify(be, org.mockito.Mockito.atLeastOnce())
            .setData(org.mockito.Mockito.eq(LivingComponents.CONTAINER_FLUID_DATA.value()), captor.capture());
        assertTrue(captor.getValue().isEmpty(),
            "源清空 ⇒ 附件必须写回 EMPTY —— 否则重进存档从附件回填复活（跨存档残留）");
    }

    @Test
    @DisplayName("端到端：LivingFluidFunction 已注册进自维持清单（纯源容器的基础）")
    void fluidDriver_isRegisteredSelfSustaining() {
        boolean found = LivingItemManager.getSelfSustainingFunctions().stream()
            .anyMatch(f -> f instanceof LivingFluidFunction);
        assertTrue(found,
            "驱动必须进自维持清单，否则「容器里没有活物品」时流体不跑");
    }

    // ⚠️ 「残留红石归零」的端到端测试已搬到 domain/redstone/ContainerRedstoneIntegrationTest
    //（2026-10-08）—— 它测的是红石层的驱动链路，本不该住在流体的测试类里；
    // 且 1b-2c 的 zeroResidualRedstone 已删，归零职责改由 LivingRedstoneFunction 的守卫接管。
}
