package com.qiqi.li.living.core.accessor;

import java.util.Set;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SlotInfoProvider;
import com.qiqi.li.living.container.ContainerSync;

/**
 * 普通槽位访问器 —— 直接操作容器槽位。
 *
 * <p>适用于非活物品的普通槽位，通过 {@link SlotInfoProvider} 读写物品，
 * 通过 {@link ContainerSync} 同步到客户端。</p>
 */
public class PlainSlotAccessor implements SlotAccessor {

    private final SlotInfoProvider slotInfo;
    private final ContainerSync sync;
    private final int slot;
    private final Set<Integer> transferredTargetSlots;

    PlainSlotAccessor(ContainerContext containerCtx, int slot, Set<Integer> transferredTargetSlots) {
        this.slotInfo = containerCtx;
        this.sync = containerCtx;
        this.slot = slot;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack sourceStack = slotInfo.getItem(slot);
        if (sourceStack.isEmpty()) return ItemStack.EMPTY;

        int transferAmount = Math.min(sourceStack.getCount(), amount);
        ItemStack extracted = sourceStack.copy();
        extracted.setCount(transferAmount);

        ItemStack remaining = sourceStack.copy();
        remaining.shrink(transferAmount);
        slotInfo.setItem(slot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);

        return extracted;
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        ItemStack sourceStack = slotInfo.getItem(slot);
        if (sourceStack.isEmpty()) return ItemStack.EMPTY;

        int transferAmount = Math.min(sourceStack.getCount(), amount);
        ItemStack result = sourceStack.copy();
        result.setCount(transferAmount);
        return result;
    }

    @Override
    public int insert(ItemStack stack) {
        ItemStack targetStack = slotInfo.getItem(slot);
        int slotLimit = slotInfo.getSlotLimit(slot);

        if (targetStack.isEmpty()) {
            if (slotInfo.simulateInsertItem(slot, stack) <= 0) return 0;
            int maxStackSize = Math.min(slotLimit, stack.getMaxStackSize());
            int actual = Math.min(stack.getCount(), maxStackSize);
            ItemStack toInsert = stack.copy();
            toInsert.setCount(actual);
            slotInfo.setItem(slot, toInsert);
            stack.shrink(actual);
            return actual;
        }

        int maxStackSize = Math.min(slotLimit, targetStack.getMaxStackSize());
        if (targetStack.is(stack.getItem()) && targetStack.getCount() < maxStackSize) {
            int space = maxStackSize - targetStack.getCount();
            int actual = Math.min(stack.getCount(), space);
            ItemStack grown = targetStack.copy();
            grown.grow(actual);
            slotInfo.setItem(slot, grown);
            stack.shrink(actual);
            return actual;
        }

        return 0;
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        ItemStack targetStack = slotInfo.getItem(slot);

        if (targetStack.isEmpty()) {
            return slotInfo.simulateInsertItem(slot, stack);
        }

        int slotLimit = slotInfo.getSlotLimit(slot);
        int maxStackSize = Math.min(slotLimit, targetStack.getMaxStackSize());
        if (targetStack.is(stack.getItem()) && targetStack.getCount() < maxStackSize) {
            int space = maxStackSize - targetStack.getCount();
            return Math.min(stack.getCount(), space);
        }

        return 0;
    }

    @Override
    public void rollback(ItemStack stack) {
        ItemStack current = slotInfo.getItem(slot);
        if (current.isEmpty()) {
            slotInfo.setItem(slot, stack);
        } else if (current.is(stack.getItem())) {
            ItemStack grown = current.copy();
            grown.grow(stack.getCount());
            slotInfo.setItem(slot, grown);
        }
    }

    @Override
    public boolean isEmpty() {
        return slotInfo.getItem(slot).isEmpty();
    }

    @Override
    public boolean isFull() {
        ItemStack stack = slotInfo.getItem(slot);
        return !stack.isEmpty() && stack.getCount() >= slotInfo.getSlotLimit(slot);
    }

    @Override
    public void markTransferred() {
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(slot);
        }
    }

    @Override
    public void sync() {
        sync.syncSlotToClients(slot, slotInfo.getItem(slot));
    }
}