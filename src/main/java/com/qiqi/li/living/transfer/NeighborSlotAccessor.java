package com.qiqi.li.living.transfer;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 邻居容器槽位访问器 —— 包装相邻容器的 IItemHandler 槽位。
 *
 * <p>用于跨容器传输场景，将邻居容器的 {@link IItemHandler} 槽位
 * 统一为 {@link SlotAccessor} 接口，使跨容器传输也能复用
 * {@link FilteredSlotAccessor} 过滤和统一的传输逻辑。</p>
 */
public class NeighborSlotAccessor implements SlotAccessor {

    private final IItemHandler handler;
    private final int slot;

    public NeighborSlotAccessor(IItemHandler handler, int slot) {
        this.handler = handler;
        this.slot = slot;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        int toExtract = Math.min(amount, stack.getCount());
        return handler.extractItem(slot, toExtract, false);
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        int toExtract = Math.min(amount, stack.getCount());
        return handler.extractItem(slot, toExtract, true);
    }

    @Override
    public int insert(ItemStack stack) {
        ItemStack original = stack.copy();
        ItemStack remaining = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false);
        int inserted = original.getCount() - remaining.getCount();
        stack.setCount(remaining.getCount());
        return inserted;
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        ItemStack remaining = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), true);
        return stack.getCount() - remaining.getCount();
    }

    @Override
    public void rollback(ItemStack stack) {
        ItemStack current = handler.getStackInSlot(slot);
        if (current.isEmpty() || ItemStack.isSameItemSameComponents(current, stack)) {
            handler.insertItem(slot, stack, false);
        } else {
            ItemHandlerHelper.insertItem(handler, stack, false);
        }
    }

    @Override
    public boolean isEmpty() {
        return handler.getStackInSlot(slot).isEmpty();
    }

    @Override
    public boolean isFull() {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack.isEmpty()) return false;
        return stack.getCount() >= handler.getSlotLimit(slot);
    }

    @Override
    public void markTransferred() {
        // 邻居容器没有级联防护集合，空实现
    }

    @Override
    public void sync() {
        // 邻居容器没有同步需求，空实现
    }

    public IItemHandler getHandler() {
        return handler;
    }

    public int getSlot() {
        return slot;
    }
}