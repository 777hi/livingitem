package com.qiqi.li.living.domain.ender;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;

/**
 * 活末影箱的 Forge 物品处理器。
 *
 * <p>若活末影箱已绑定玩家，则把读写作代理到该玩家的末影箱背包（{@code IItemHandler}），
 * 供外部传输系统访问；未绑定或玩家离线时槽位为 0、读写无效。</p>
 */
public class LivingEnderChestItemHandler implements IItemHandler {

    private final ItemStack enderChestStack;
    private final Player boundPlayer;
    private final IItemHandler enderChestHandler;

    public LivingEnderChestItemHandler(ItemStack enderChestStack) {
        this.enderChestStack = enderChestStack;
        if (LivingEnderChestFunction.hasBoundPlayer(enderChestStack)) {
            this.boundPlayer = null;
            this.enderChestHandler = null;
        } else {
            this.boundPlayer = null;
            this.enderChestHandler = null;
        }
    }

    public LivingEnderChestItemHandler(ItemStack enderChestStack, Player boundPlayer) {
        this.enderChestStack = enderChestStack;
        this.boundPlayer = boundPlayer;
        if (boundPlayer != null) {
            this.enderChestHandler = new InvWrapper(boundPlayer.getEnderChestInventory());
        } else {
            this.enderChestHandler = null;
        }
    }

    @Override
    public int getSlots() {
        if (boundPlayer != null && enderChestHandler != null) {
            return enderChestHandler.getSlots();
        }
        return 0;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (enderChestHandler != null) {
            return enderChestHandler.getStackInSlot(slot);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (enderChestHandler != null) {
            return enderChestHandler.insertItem(slot, stack, simulate);
        }
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (enderChestHandler != null) {
            return enderChestHandler.extractItem(slot, amount, simulate);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        if (enderChestHandler != null) {
            return enderChestHandler.getSlotLimit(slot);
        }
        return 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (enderChestHandler != null) {
            return enderChestHandler.isItemValid(slot, stack);
        }
        return false;
    }
}