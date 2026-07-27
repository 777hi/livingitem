package com.qiqi.li.living.core.accessor;

import java.util.List;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerIdentity;
import com.qiqi.li.living.container.ContainerSync;
import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.function.LivingChestFunction;
import net.minecraft.server.MinecraftServer;

import java.util.Set;

public class LivingChestAccessor implements SlotAccessor {

    private final ContainerIdentity identity;
    private final ContainerSync sync;
    private final int slot;
    private final ItemStack chestStack;
    private final int capacityPerChest;
    private final Set<Integer> transferredTargetSlots;

    LivingChestAccessor(ContainerContext containerCtx, int slot, ItemStack chestStack,
                        int capacityPerChest, Set<Integer> transferredTargetSlots) {
        this.identity = containerCtx;
        this.sync = containerCtx;
        this.slot = slot;
        this.chestStack = chestStack;
        this.capacityPerChest = capacityPerChest;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    /**
     * 尝试创建 LivingChestAccessor（用于注册式工厂）。
     *
     * @return 如果是活箱子则返回 Accessor，否则返回 null
     */
    public static SlotAccessor tryCreate(MinecraftServer server, ContainerContext containerCtx, int slot,
                                          FilterData filterData, Set<Integer> transferredTargetSlots) {
        ItemStack stack = containerCtx.getItem(slot);
        if (!LivingChestFunction.isLivingChest(stack)) {
            return null;
        }
        int capacity = LivingChestFunction.getCapacity(containerCtx);
        return new LivingChestAccessor(containerCtx, slot, stack, capacity, transferredTargetSlots);
    }

    private HolderLookup.Provider getRegistries() {
        Level level = identity.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.registryAccess();
        }
        return null;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        if (LivingChestFunction.isStorageEmpty(chestStack)) {
            return ItemStack.EMPTY;
        }
        return LivingChestFunction.extractItem(chestStack, amount);
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        if (LivingChestFunction.isStorageEmpty(chestStack)) {
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
        if (LivingChestFunction.isStorageFull(chestStack)) {
            return 0;
        }

        int originalCount = stack.getCount();
        LivingChestFunction.insertItem(chestStack, stack, getRegistries());
        return originalCount - stack.getCount();
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        HolderLookup.Provider registries = getRegistries();
        if (LivingChestFunction.isStorageFull(chestStack) || LivingChestFunction.isByteFull(chestStack, registries)) {
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
        return LivingChestFunction.isStorageEmpty(chestStack);
    }

    @Override
    public boolean isFull() {
        HolderLookup.Provider registries = getRegistries();
        return LivingChestFunction.isStorageFull(chestStack) || LivingChestFunction.isByteFull(chestStack, registries);
    }

    @Override
    public void markTransferred() {
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(slot);
        }
    }

    @Override
    public void sync() {
        sync.syncSlotToClients(slot, sync.getItem(slot));
    }
}