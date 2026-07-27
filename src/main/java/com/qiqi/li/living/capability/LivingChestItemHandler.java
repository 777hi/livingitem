package com.qiqi.li.living.capability;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.function.LivingChestFunction;

public class LivingChestItemHandler implements IItemHandler {

    private final ItemStack chestStack;
    private final int slots;

    public LivingChestItemHandler(ItemStack chestStack) {
        this.chestStack = chestStack;
        this.slots = LivingChestFunction.CHEST_SLOTS;
    }

    @Override
    public int getSlots() {
        return slots;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= slots) return ItemStack.EMPTY;
        if (chestStack.getCount() > 1) return ItemStack.EMPTY;
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return ItemStack.EMPTY;
        NonNullList<ItemStack> list = NonNullList.withSize(slots, ItemStack.EMPTY);
        contents.copyInto(list);
        return list.get(slot).copy();
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (chestStack.getCount() > 1) return stack;
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (slot < 0 || slot >= slots) return stack;
        if (!isItemValid(slot, stack)) return stack;
        if (InternalStorageComponent.isByteFull(chestStack)) return stack;

        if (simulate) {
            return simulateInsertInternal(slot, stack);
        }

        int originalCount = stack.getCount();
        InternalStorageComponent.insertItem(chestStack, stack.copy());
        int inserted = originalCount - stack.getCount();
        if (inserted <= 0) return stack.copy();
        ItemStack remainder = stack.copy();
        remainder.setCount(originalCount - inserted);
        return remainder;
    }

    private ItemStack simulateInsertInternal(int targetSlot, ItemStack stack) {
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        NonNullList<ItemStack> items = NonNullList.withSize(slots, ItemStack.EMPTY);
        if (contents != null) contents.copyInto(items);

        int remaining = stack.getCount();

        ItemStack slotItem = items.get(targetSlot);
        if (slotItem.isEmpty()) {
            remaining -= Math.min(remaining, stack.getMaxStackSize());
        } else if (ItemStack.isSameItemSameComponents(slotItem, stack)) {
            int space = slotItem.getMaxStackSize() - slotItem.getCount();
            remaining -= Math.min(remaining, space);
        }

        if (remaining <= 0) return ItemStack.EMPTY;
        ItemStack result = stack.copy();
        result.setCount(remaining);
        return result;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (chestStack.getCount() > 1) return ItemStack.EMPTY;
        if (amount <= 0) return ItemStack.EMPTY;
        if (slot < 0 || slot >= slots) return ItemStack.EMPTY;

        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return ItemStack.EMPTY;
        NonNullList<ItemStack> items = NonNullList.withSize(slots, ItemStack.EMPTY);
        contents.copyInto(items);

        ItemStack slotItem = items.get(slot);
        if (slotItem.isEmpty()) return ItemStack.EMPTY;

        int toExtract = Math.min(amount, slotItem.getCount());
        ItemStack result = slotItem.copyWithCount(toExtract);

        if (!simulate) {
            slotItem.shrink(toExtract);
            if (slotItem.isEmpty()) {
                items.set(slot, ItemStack.EMPTY);
            }
            chestStack.set(net.minecraft.core.component.DataComponents.CONTAINER,
                ItemContainerContents.fromItems(items));
        }

        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (chestStack.getCount() > 1) return false;
        if (stack.isEmpty()) return false;
        return !InternalStorageComponent.isByteFull(chestStack);
    }
}