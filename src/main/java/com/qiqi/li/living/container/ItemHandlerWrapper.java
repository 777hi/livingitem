package com.qiqi.li.living.container;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * IItemHandler → Container 适配器。
 * 使支持 IItemHandler 能力（如抽屉、机器等）的模组方块也能被活物品系统访问。
 */
public record ItemHandlerWrapper(IItemHandler handler) implements Container {

    @Override
    public int getContainerSize() {
        return handler.getSlots();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return handler.extractItem(slot, amount, false);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        int count = handler.getStackInSlot(slot).getCount();
        return handler.extractItem(slot, count, false);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        handler.extractItem(slot, Integer.MAX_VALUE, false);
        handler.insertItem(slot, stack, false);
    }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(net.minecraft.world.entity.player.Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.extractItem(i, Integer.MAX_VALUE, false);
        }
    }
}