package com.qiqi.li.living.domain.ender;

import java.util.List;
import java.util.Set;

import com.qiqi.li.living.transfer.SlotAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerIdentity;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.container.ContainerSync;
import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.function.LivingChestFunction;
import net.minecraft.server.MinecraftServer;

public class LivingChestAccessor implements SlotAccessor {

    private final ContainerIdentity identity;
    private final ContainerSync sync;
    private final int slot;
    private final ItemStack chestStack;
    private final int capacityPerChest;
    private final Set<Integer> transferredTargetSlots;
    private final ContainerSnapshot.ChestSnapshot chestSnap;

    LivingChestAccessor(ContainerContext containerCtx, int slot, ItemStack chestStack,
                        int capacityPerChest, Set<Integer> transferredTargetSlots,
                        ContainerSnapshot.ChestSnapshot chestSnap) {
        this.identity = containerCtx;
        this.sync = containerCtx;
        this.slot = slot;
        this.chestStack = chestStack;
        this.capacityPerChest = capacityPerChest;
        this.transferredTargetSlots = transferredTargetSlots;
        this.chestSnap = chestSnap;
    }

    public static SlotAccessor tryCreate(MinecraftServer server, ContainerContext containerCtx, int slot,
                                          FilterData filterData, Set<Integer> transferredTargetSlots,
                                          ContainerSnapshot snapshot) {
        ItemStack stack = containerCtx.getItem(slot);
        if (!LivingChestFunction.isLivingChest(stack)) {
            return null;
        }
        int capacity = LivingChestFunction.getCapacity(containerCtx);
        ContainerSnapshot.ChestSnapshot chestSnap = snapshot.getChestSnapshot(slot);
        return new LivingChestAccessor(containerCtx, slot, stack, capacity, transferredTargetSlots, chestSnap);
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
        if (chestSnap.usedSlots() == 0) {
            return ItemStack.EMPTY;
        }
        return LivingChestFunction.extractItem(chestStack, amount);
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        if (chestSnap.usedSlots() == 0) {
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
        if (chestSnap.isFull()) {
            return 0;
        }

        int originalCount = stack.getCount();
        LivingChestFunction.insertItem(chestStack, stack, getRegistries());
        return originalCount - stack.getCount();
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        if (chestSnap.isFull() || chestSnap.isByteFull()) {
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
        return chestSnap.usedSlots() == 0;
    }

    @Override
    public boolean isFull() {
        return chestSnap.isFull() || chestSnap.isByteFull();
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