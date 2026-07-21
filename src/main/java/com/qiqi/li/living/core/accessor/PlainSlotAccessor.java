package com.qiqi.li.living.core.accessor;

import java.util.Set;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;

/**
 * 普通槽位访问器 —— 直接操作容器槽位。
 *
 * <p>适用于非活物品的普通槽位，直接通过 {@link ContainerContext#getItem}/{@link ContainerContext#setItem} 读写。</p>
 */
public class PlainSlotAccessor implements SlotAccessor {

    private final ContainerContext containerCtx;
    private final int slot;
    private final Set<Integer> transferredTargetSlots;

    PlainSlotAccessor(ContainerContext containerCtx, int slot, Set<Integer> transferredTargetSlots) {
        this.containerCtx = containerCtx;
        this.slot = slot;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack sourceStack = containerCtx.getItem(slot);
        if (sourceStack.isEmpty()) return ItemStack.EMPTY;

        int transferAmount = Math.min(sourceStack.getCount(), amount);
        ItemStack extracted = sourceStack.copy();
        extracted.setCount(transferAmount);

        sourceStack.shrink(transferAmount);
        containerCtx.setItem(slot, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

        return extracted;
    }

    @Override
    public int insert(ItemStack stack) {
        ItemStack targetStack = containerCtx.getItem(slot);

        if (targetStack.isEmpty()) {
            containerCtx.setItem(slot, stack.copy());
            int inserted = stack.getCount();
            stack.setCount(0);
            return inserted;
        }

        if (targetStack.is(stack.getItem()) && targetStack.getCount() < targetStack.getMaxStackSize()) {
            int space = targetStack.getMaxStackSize() - targetStack.getCount();
            int actual = Math.min(stack.getCount(), space);
            targetStack.grow(actual);
            containerCtx.setItem(slot, targetStack);
            stack.shrink(actual);
            return actual;
        }

        return 0;
    }

    @Override
    public void rollback(ItemStack stack) {
        ItemStack current = containerCtx.getItem(slot);
        if (current.isEmpty()) {
            containerCtx.setItem(slot, stack);
        } else if (current.is(stack.getItem())) {
            current.grow(stack.getCount());
            containerCtx.setItem(slot, current);
        }
    }

    @Override
    public boolean isEmpty() {
        return containerCtx.getItem(slot).isEmpty();
    }

    @Override
    public boolean isFull() {
        ItemStack stack = containerCtx.getItem(slot);
        return !stack.isEmpty() && stack.getCount() >= stack.getMaxStackSize();
    }

    @Override
    public void markTransferred() {
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(slot);
        }
    }

    @Override
    public void sync() {
        containerCtx.syncSlotToClients(slot, containerCtx.getItem(slot));
    }
}