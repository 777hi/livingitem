package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.SimpleContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 流体搬运的<b>物品守恒</b>回归测试（2026-11-01）。
 *
 * <p>守的是 {@code ContainerFluidData.tryMoveInto} 的两条容量口径。这两条此前都错，
 * 且都是「写目标时被 {@code clampToSlotLimit} 静默截断、清源却无条件」⇒ 物品凭空消失：</p>
 * <ul>
 *   <li><b>空槽路径不查容量</b>：抽屉 512 上限 → 普通槽 64 上限，搬 500 个只到 64，
 *       源槽清空 ⇒ 丢 436。原先<b>无人知晓</b>（未登记缺陷）。</li>
 *   <li><b>合并路径按物品上限而非槽位上限</b>（缺陷 A2）：目标槽上限 16、已有 10、
 *       物品上限 64 ⇒ 以为能进 54，实际只进 6 ⇒ 丢 48。</li>
 * </ul>
 *
 * <p><b>为什么用 per-slot 上限的 handler</b>：真实整合包里「抽屉（大上限）→ 普通箱（64）」
 * 是最常见的异构组合。用统一上限的 handler 复现不出这两条 —— 源槽也会被 clamp，
 * 掩盖「源被无条件清空」这个真正的病灶。</p>
 *
 * <p>⚠️ 本测试<b>不</b>断言「准入」行为：推动不校验 {@code isItemValid} 是 A3 定案，
 * 由 {@code ContainerFluidDataTest#pushItems_ignoresSlotAdmission} 钉住。</p>
 */
class ContainerFluidConservationTest {

    /** 槽位上限按槽可配置的 handler（真实 IItemHandler 语义，setItem 走 setStackInSlot）。 */
    private static class LimitedHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        final int[] limits;

        LimitedHandler(int[] limits) {
            this.limits = limits;
            this.slots = new ItemStack[limits.length];
            for (int i = 0; i < slots.length; i++) slots[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public int getSlotLimit(int slot) { return limits[slot]; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            int maxStack = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                int toInsert = Math.min(stack.getCount(), maxStack);
                if (!simulate) slots[slot] = stack.copyWithCount(toInsert);
                return remainderOf(stack, toInsert);
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = maxStack - existing.getCount();
                if (space <= 0) return stack;
                int toInsert = Math.min(stack.getCount(), space);
                if (!simulate) slots[slot].grow(toInsert);
                return remainderOf(stack, toInsert);
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

        private static ItemStack remainderOf(ItemStack stack, int inserted) {
            ItemStack remainder = stack.copy();
            remainder.shrink(inserted);
            return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
        }
    }

    /**
     * 复刻「{@code setStackInSlot} 对指定槽静默失败」的容器：写入被无声丢弃，
     * 读回永远是旧内容。守卫测试直接对接这类违约行为 —— 它是 I-C12 源侧核验
     * 要对抗的三种第三方违约之一（{@code insertItem} 撒谎 / {@code getSlotLimit} 撒谎 /
     * {@code setStackInSlot} 静默失败）。
     */
    private static class SourceWriteIgnoringHandler extends LimitedHandler {
        final int ignoredSlot;

        SourceWriteIgnoringHandler(int[] limits, int ignoredSlot) {
            super(limits);
            this.ignoredSlot = ignoredSlot;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (slot == ignoredSlot) return;   // 静默违约：写入直接丢弃
            super.setStackInSlot(slot, stack);
        }
    }

    /**
     * 9 格（1×9 单行）：<b>slot 7 是抽屉</b>（512），其余普通槽（64）。
     *
     * <p>为什么把源放在 slot 7：水从 slot 0 扩散到 slot 7，物品从 slot 7 被推到
     * <b>slot 8 —— 水流之外</b>。离开水流的物品不再出现在 {@code flows} 里，
     * 因而不会被后续拍继续推动，测试才只观测「一次搬运」。
     * 若把目标放在水流中段（如 slot 2），物品会被一拍一拍推到容器尽头，
     * 断言看到的就不是搬运结果而是「它跑哪去了」。</p>
     */
    private static int[] drawerLimits() {
        int[] limits = new int[9];
        java.util.Arrays.fill(limits, 64);
        limits[7] = 512;
        return limits;
    }

    private static LimitedHandler drawerIntoBoxes() {
        return new LimitedHandler(drawerLimits());
    }

    private static int total(SimpleContainerContext ctx) {
        int sum = 0;
        for (int i = 0; i < ctx.getSize(); i++) sum += ctx.getItem(i).getCount();
        return sum;
    }

    private static ContainerFluidData waterFrom(int sourceSlot) {
        ContainerFluidData fluid = new ContainerFluidData();
        fluid.registerGeneratedSource(sourceSlot, Fluids.WATER.getFluidType());
        return fluid;
    }

    @BeforeEach
    void baselineWaterBehavior() {
        FluidFlowBehaviors.clear();
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, 0));
    }

    @Test
    @DisplayName("空槽路径：抽屉 500 个 → 目标槽上限 64，只搬 64、源留 436（不丢）")
    void emptyTarget_doesNotDiscardOverflow() {
        LimitedHandler h = drawerIntoBoxes();
        // ⚠️ 物品必须在「流动格」：pushFluidItems 对源格直接 continue（源格只接受显式投放）
        h.slots[7] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        waterFrom(0).tick(ctx);

        assertEquals(64, ctx.getItem(8).getCount(), "目标槽只收得下 64");
        assertEquals(436, ctx.getItem(7).getCount(), "装不下的必须留在源槽");
        assertEquals(500, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("合并路径：目标上限 16、已有 10 → 只进 6（按槽位上限，不是物品上限 64）")
    void merge_usesSlotLimitNotItemMaxStackSize() {
        LimitedHandler h = drawerIntoBoxes();
        h.limits[8] = 16;                                  // 小槽容器
        h.slots[7] = new ItemStack(Items.REDSTONE, 500);
        h.slots[8] = new ItemStack(Items.REDSTONE, 10);    // 实际还能进 6
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        waterFrom(0).tick(ctx);

        assertEquals(16, ctx.getItem(8).getCount(), "目标槽封顶在槽位上限 16");
        assertEquals(494, ctx.getItem(7).getCount(), "只扣掉真正搬过去的 6 个");
        assertEquals(510, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("目标槽已满：一个都不搬，源槽原样")
    void fullTarget_movesNothing() {
        LimitedHandler h = drawerIntoBoxes();
        h.slots[7] = new ItemStack(Items.REDSTONE, 500);
        h.slots[8] = new ItemStack(Items.REDSTONE, 64);    // 恰好满
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        waterFrom(0).tick(ctx);

        assertEquals(500, ctx.getItem(7).getCount(), "源槽不动");
        assertEquals(64, ctx.getItem(8).getCount(), "目标槽不动");
        assertEquals(564, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("异类占位：目标槽是别的物品 ⇒ 不搬也不覆盖")
    void foreignItemInTarget_doesNotOverwrite() {
        LimitedHandler h = drawerIntoBoxes();
        h.slots[7] = new ItemStack(Items.REDSTONE, 500);
        h.slots[8] = new ItemStack(Items.GOLD_INGOT, 3);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        waterFrom(0).tick(ctx);

        assertEquals(Items.GOLD_INGOT, ctx.getItem(8).getItem(), "目标槽保持原物");
        assertEquals(3, ctx.getItem(8).getCount(), "目标槽数量不变");
        assertEquals(503, total(ctx), "总量守恒");
    }

    // ===== I-C12 源侧核验（2026-11-05）：源槽 setStackInSlot 静默失败 ⇒ 整拍撤销，不复制 =====

    @Test
    @DisplayName("源写回静默失败（合并目标）：目标撤销到 10、源保持 500，总量守恒")
    void sourceWriteFails_mergeTarget_isFullyReverted() {
        SourceWriteIgnoringHandler h = new SourceWriteIgnoringHandler(drawerLimits(), 7);
        h.slots[7] = new ItemStack(Items.REDSTONE, 500);
        h.slots[8] = new ItemStack(Items.REDSTONE, 10);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        waterFrom(0).tick(ctx);

        assertEquals(10, ctx.getItem(8).getCount(), "目标槽撤销回移动前（不保留已推入的 54）");
        assertEquals(500, ctx.getItem(7).getCount(), "源槽写回被静默丢弃 ⇒ 原样保留");
        assertEquals(510, total(ctx), "总量守恒：目标已增 + 源未减 = 复制，必须整拍回退");
    }

    @Test
    @DisplayName("源写回静默失败（空目标）：目标撤销为空、源保持 500，总量守恒")
    void sourceWriteFails_emptyTarget_isFullyReverted() {
        SourceWriteIgnoringHandler h = new SourceWriteIgnoringHandler(drawerLimits(), 7);
        h.slots[7] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        waterFrom(0).tick(ctx);

        assertEquals(0, ctx.getItem(8).getCount(), "目标槽撤销为空");
        assertEquals(500, ctx.getItem(7).getCount(), "源槽原样保留");
        assertEquals(500, total(ctx), "总量守恒");
    }
}
