package com.qiqi.li.living.core.accessor;

import java.util.List;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.function.LivingChestFunction;

public class LivingChestAccessor implements SlotAccessor {

    private final ContainerContext containerCtx;
    private final int slot;
    private final ItemStack chestStack;
    private final int capacityPerChest;
    private final Set<Integer> transferredTargetSlots;

    LivingChestAccessor(ContainerContext containerCtx, int slot, ItemStack chestStack,
                        int capacityPerChest, Set<Integer> transferredTargetSlots) {
        this.containerCtx = containerCtx;
        this.slot = slot;
        this.chestStack = chestStack;
        this.capacityPerChest = capacityPerChest;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    private HolderLookup.Provider getRegistries() {
        net.minecraft.world.level.Level level = containerCtx.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.registryAccess();
        }
        return null;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        if (InternalStorageComponent.isStorageEmpty(chestStack)) {
            return ItemStack.EMPTY;
        }
        return LivingChestFunction.extractItem(chestStack, amount);
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        if (InternalStorageComponent.isStorageEmpty(chestStack)) {
            return ItemStack.EMPTY;
        }

        List<ItemStack> items = LivingChestFunction.getItems(chestStack);
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                ItemStack result = item.copy();
                result.setCount(Math.min(amount, item.getCount()));
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public int insert(ItemStack stack) {
        if (InternalStorageComponent.isStorageFull(chestStack)) {
            return 0;
        }

        int originalCount = stack.getCount();
        LivingChestFunction.insertItem(chestStack, stack, getRegistries());
        return originalCount - stack.getCount();
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        HolderLookup.Provider registries = getRegistries();
        if (InternalStorageComponent.isStorageFull(chestStack) || InternalStorageComponent.isByteFull(chestStack, registries)) {
            return 0;
        }

        List<ItemStack> items = LivingChestFunction.getItems(chestStack);
        int canAccept = 0;
        int remaining = stack.getCount();

        for (ItemStack slotItem : items) {
            if (remaining <= 0) break;
            if (slotItem.isEmpty()) {
                int toAdd = Math.min(remaining, stack.getMaxStackSize());
                canAccept += toAdd;
                remaining -= toAdd;
            } else if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(slotItem, stack)) {
                int spaceAvailable = slotItem.getMaxStackSize() - slotItem.getCount();
                if (spaceAvailable > 0) {
                    int toAdd = Math.min(remaining, spaceAvailable);
                    canAccept += toAdd;
                    remaining -= toAdd;
                }
            }
        }

        return canAccept;
    }

    @Override
    public void rollback(ItemStack stack) {
        LivingChestFunction.insertItem(chestStack, stack);
    }

    @Override
    public boolean isEmpty() {
        return InternalStorageComponent.isStorageEmpty(chestStack);
    }

    @Override
    public boolean isFull() {
        HolderLookup.Provider registries = getRegistries();
        return InternalStorageComponent.isStorageFull(chestStack) || InternalStorageComponent.isByteFull(chestStack, registries);
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