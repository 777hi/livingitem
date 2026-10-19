package com.qiqi.li.living.domain.chest;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import com.qiqi.li.living.domain.chest.LivingChestFunction;

/**
 * 活箱子的 Forge 物品处理器。
 *
 * <p>以 {@code IItemHandler} 形式暴露活箱子内部存储（27 槽），供原版 / Forge 传输系统与外部接口读写；
 * 读写均委托 {@link LivingChestFunction} 并受堆叠数与字节上限约束。</p>
 *
 * <p>⚠️ <b>为什么必须实现 {@link IItemHandlerModifiable}</b>
 * （2026-10-18，契约 I-C3）：本类的 {@link #insertItem(int, ItemStack, boolean)} 真实写入走
 * {@link LivingChestFunction#insertItem} —— 那是<b>整库插入</b>，<b>忽略 slot 参数</b>
 * （与精妙背包同语义）。若 {@code setItem} 用它写回，活箱子里的活物品就会「改槽 17」变成
 * 「搬到编号最低的空槽」。提供 {@link #setStackInSlot} 后写回走精确槽位，插入语义不受影响。</p>
 */
public class LivingChestItemHandler implements IItemHandlerModifiable {

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
        if (LivingChestFunction.isByteFull(chestStack)) return stack;

        if (simulate) {
            return simulateInsertInternal(slot, stack);
        }

        int originalCount = stack.getCount();
        LivingChestFunction.insertItem(chestStack, stack.copy());
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

    /**
     * 精确覆盖指定槽位 —— 契约 I-C3 的写回原语。
     *
     * <p>与 {@link #insertItem(int, ItemStack, boolean)} 的区别：后者是「把物品放进这个箱子」
     * （整库找位置），本方法是「让槽 slot 变成 stack」。活物品的进度 / 燃料 / 产物写回必须走这条。</p>
     */
    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (chestStack.getCount() > 1) return;
        if (slot < 0 || slot >= slots) return;
        java.util.List<ItemStack> items = LivingChestFunction.getItems(chestStack);
        items.set(slot, stack.copy());
        LivingChestFunction.setItems(chestStack, items);
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
        return !LivingChestFunction.isByteFull(chestStack);
    }
}