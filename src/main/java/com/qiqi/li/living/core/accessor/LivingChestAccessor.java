package com.qiqi.li.living.core.accessor;

import java.util.List;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.qiqi.li.living.function.LivingChestFunction;

/**
 * 活箱子槽位访问器 —— 通过 LivingChestFunction API 读写活箱子存储。
 *
 * <p>extract 采用"预查+精确提取"策略：先遍历所有虚拟箱子找到能通过过滤的物品类型，
 * 再按类型精确提取，避免"提取→检查→退回"的循环。</p>
 */
public class LivingChestAccessor implements SlotAccessor {

    private final MinecraftServer server;
    private final ContainerContext containerCtx;
    private final int slot;
    private final ItemStack chestStack;
    private final int capacityPerChest;
    private final ComponentState filterState;
    private final Set<Integer> transferredTargetSlots;

    LivingChestAccessor(MinecraftServer server, ContainerContext containerCtx, int slot, ItemStack chestStack,
                        int capacityPerChest, ComponentState filterState, Set<Integer> transferredTargetSlots) {
        this.server = server;
        this.containerCtx = containerCtx;
        this.slot = slot;
        this.chestStack = chestStack;
        this.capacityPerChest = capacityPerChest;
        this.filterState = filterState;
        this.transferredTargetSlots = transferredTargetSlots;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ComponentState chestState = LivingChestFunction.getStorageState(chestStack);
        if (InternalStorageComponent.isStorageEmpty(chestState)) {
            return ItemStack.EMPTY;
        }

        if (filterState != null) {
            List<ItemStack> merged = LivingChestFunction.getMergedStorage(server, chestStack, capacityPerChest);
            ItemStack matchingType = null;
            for (ItemStack item : merged) {
                if (!item.isEmpty() && ItemFilterComponent.allows(filterState, item)) {
                    matchingType = item;
                    break;
                }
            }
            if (matchingType == null) {
                return ItemStack.EMPTY;
            }
            return LivingChestFunction.extractItem(server, chestStack, matchingType, amount, capacityPerChest);
        }

        return LivingChestFunction.extractItem(server, chestStack, amount, capacityPerChest);
    }

    @Override
    public int insert(ItemStack stack) {
        ComponentState chestState = LivingChestFunction.getStorageState(chestStack);
        if (InternalStorageComponent.isStorageFull(chestState, capacityPerChest)) {
            return 0;
        }

        int originalCount = stack.getCount();
        LivingChestFunction.insertItem(server, chestStack, stack, capacityPerChest);
        return originalCount - stack.getCount();
    }

    @Override
    public void rollback(ItemStack stack) {
        LivingChestFunction.insertItem(server, chestStack, stack, capacityPerChest);
    }

    @Override
    public boolean isEmpty() {
        ComponentState state = LivingChestFunction.getStorageState(chestStack);
        return InternalStorageComponent.isStorageEmpty(state);
    }

    @Override
    public boolean isFull() {
        ComponentState state = LivingChestFunction.getStorageState(chestStack);
        return InternalStorageComponent.isStorageFull(state, capacityPerChest);
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