package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 容器边界守卫测试 —— 把 [container-boundary.md](../../../../../../docs/system-design/container-boundary.md)
 * 的 I-C3 / I-C4 / I-C7 钉成可执行断言。
 *
 * <h3>为什么需要这两个替身</h3>
 * <p>本模组假设「槽位是精确地址」，而 {@code IItemHandler} 契约只承诺「物品被接受」。
 * 真实存在的第三方容器（精妙背包 SophisticatedCore）就是按后一种语义实现的：</p>
 * <ul>
 *   <li>{@link SlotIgnoringHandler} —— {@code insertItem(slot, …)} 在 {@code simulate=false} 时
 *       <b>丢弃 slot</b>，改走整库插入（同款部分满槽 → 编号最低的空槽）。
 *       源码依据：{@code InventoryHandlerSlotTracker} 的 simulate 分支与非 simulate 分支不同源。</li>
 *   <li>{@link ExtractBlockedHandler} —— {@code extractItem} 恒返回 {@code EMPTY}
 *       （等价于「输出过滤器拦截」），{@code insertItem} 正常。
 *       源码依据：{@code FilteredItemHandler} 的输出过滤器分支。</li>
 * </ul>
 *
 * <h3>守卫价值（先红后绿，2026-10-18 实测）</h3>
 * <p>S2 之前 {@code SimpleContainerContext.setItem} 是 {@code extractItem + insertItem}，
 * 三条断言全红：</p>
 * <pre>
 *   writeBack_landsInTargetSlot      → 物品必须写回槽 3  ==> expected: &lt;1&gt; but was: &lt;0&gt;
 *   writeBack_conservesItemCount     → 写回不得改变物品总数 ==> expected: &lt;1&gt; but was: &lt;2&gt;
 *   simulateAndRealAgreeOnTargetSlot → 真实写入必须落槽 4  ==> expected: &lt;1&gt; but was: &lt;0&gt;
 * </pre>
 * <p>S2 改为 {@code setStackInSlot} 后三条转绿。若将来有人把 {@code setItem} 改回「先抽再插」，
 * 这里会立刻红 —— 这就是它守的东西。回归入口见 {@code docs/buffer/container-compat-topic.md} §2。</p>
 */
class ContainerBoundaryContractTest {

    /** 复刻「insertItem 忽略 slot」的容器：simulate 认槽位，真实写入整库落位。 */
    private static class SlotIgnoringHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        int realInsertCalls;

        SlotIgnoringHandler(int size) {
            slots = new ItemStack[size];
            for (int i = 0; i < size; i++) slots[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return slots.length; }

        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }

        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }

        @Override public int getSlotLimit(int slot) { return 64; }

        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            if (simulate) return insertIntoSingleSlot(slot, stack, true);
            realInsertCalls++;
            return insertIntoAnySlot(stack);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = slots[slot];
            if (existing.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) {
                ItemStack remain = existing.copy();
                remain.shrink(toExtract);
                slots[slot] = remain.isEmpty() ? ItemStack.EMPTY : remain;
            }
            return result;
        }

        private ItemStack insertIntoSingleSlot(int slot, ItemStack stack, boolean simulate) {
            ItemStack existing = slots[slot];
            int maxStack = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                int n = Math.min(stack.getCount(), maxStack);
                if (!simulate) slots[slot] = stack.copyWithCount(n);
                return remainderOf(stack, n);
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = maxStack - existing.getCount();
                if (space <= 0) return stack;
                int n = Math.min(stack.getCount(), space);
                if (!simulate) slots[slot] = existing.copyWithCount(existing.getCount() + n);
                return remainderOf(stack, n);
            }
            return stack;
        }

        /** 整库插入：先塞同款部分满槽，再塞编号最低的空槽 —— 与精妙背包同语义。 */
        private ItemStack insertIntoAnySlot(ItemStack stack) {
            ItemStack remaining = stack;
            for (int i = 0; i < slots.length && !remaining.isEmpty(); i++) {
                if (ItemStack.isSameItemSameComponents(slots[i], stack)) {
                    remaining = insertIntoSingleSlot(i, remaining, false);
                }
            }
            for (int i = 0; i < slots.length && !remaining.isEmpty(); i++) {
                if (slots[i].isEmpty()) {
                    remaining = insertIntoSingleSlot(i, remaining, false);
                }
            }
            return remaining;
        }

        private static ItemStack remainderOf(ItemStack stack, int inserted) {
            ItemStack remainder = stack.copy();
            remainder.shrink(inserted);
            return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
        }

        int totalCount() {
            int n = 0;
            for (ItemStack s : slots) n += s.getCount();
            return n;
        }
    }

    /** 复刻「输出过滤器拦截抽取」：extract 恒空，insert 正常按槽位。 */
    private static class ExtractBlockedHandler extends SlotIgnoringHandler {
        ExtractBlockedHandler(int size) { super(size); }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }
    }

    private static SimpleContainerContext ctx(IItemHandlerModifiable handler) {
        return new SimpleContainerContext(handler);
    }

    @Test
    @DisplayName("I-C3 写回必须落进目标槽位（槽位不可信的 handler）")
    void writeBack_landsInTargetSlot() {
        SlotIgnoringHandler handler = new SlotIgnoringHandler(9);
        handler.slots[0] = ItemStack.EMPTY;                       // 更低的空槽：诱饵
        handler.slots[3] = new ItemStack(Items.FURNACE, 1);

        SimpleContainerContext context = ctx(handler);
        context.setItem(3, new ItemStack(Items.FURNACE, 1));

        assertEquals(1, handler.slots[3].getCount(), "物品必须写回槽 3");
        assertTrue(handler.slots[0].isEmpty(), "槽 0 必须仍为空（不得被整库插入占用）");
    }

    @Test
    @DisplayName("I-C7 写回必须守恒：抽取被拦截时不得复制物品")
    void writeBack_conservesItemCount() {
        ExtractBlockedHandler handler = new ExtractBlockedHandler(9);
        handler.slots[2] = new ItemStack(Items.FURNACE, 1);

        SimpleContainerContext context = ctx(handler);
        context.setItem(2, new ItemStack(Items.FURNACE, 1));

        assertEquals(1, handler.totalCount(), "写回不得改变物品总数");
    }

    @Test
    @DisplayName("I-C4 模拟承诺能放的数量，真实写回必须真的落进同一槽位")
    void simulateAndRealAgreeOnTargetSlot() {
        SlotIgnoringHandler handler = new SlotIgnoringHandler(9);

        SimpleContainerContext context = ctx(handler);
        ItemStack probe = new ItemStack(Items.FURNACE, 1);

        int simulated = context.simulateInsertItem(4, probe);
        assertEquals(1, simulated, "前提：模拟认为槽 4 能放 1 个");

        // ⚠️ 断言的是**本模组**的写回路径（context.setItem），不是第三方 handler 的 insertItem。
        // 后者的语义我们改不了 —— 能保证的只有「我们不用它写回」。
        context.setItem(4, probe.copy());

        assertEquals(simulated, handler.slots[4].getCount(),
            "模拟承诺的数量必须真的写进槽 4");
        assertSame(ItemStack.EMPTY, handler.slots[0], "槽 0 不得被整库重定向占用");
    }
}
