package com.qiqi.li.living.core.accessor;

import java.util.Set;
import java.util.UUID;

import net.neoforged.neoforge.items.IItemHandler;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingEnderChestFunction;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

public final class SlotAccessorFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SlotAccessorFactory() {}

    public static SlotAccessor create(MinecraftServer server, ContainerContext containerCtx, int slot,
                                       ComponentState filterState, Set<Integer> transferredTargetSlots) {
        ItemStack stack = containerCtx.getItem(slot);

        SlotAccessor raw;

        if (LivingChestFunction.isLivingChest(stack)) {
            int capacity = LivingChestFunction.getCapacity(containerCtx);
            raw = new LivingChestAccessor(containerCtx, slot, stack, capacity,
                transferredTargetSlots);
        } else if (LivingEnderChestFunction.isLivingEnderChest(stack)) {
            int ch = stack.getCount();
            UUID boundUuid = LivingEnderChestFunction.getBoundPlayerUuid(stack);
            LOGGER.debug("SlotAccessorFactory: creating LivingEnderChestAccessor, channel={}, slot={}, direct={}", ch, slot, boundUuid != null);
            if (boundUuid != null) {
                raw = new LivingEnderChestAccessor(server, ch,
                    transferredTargetSlots, boundUuid);
            } else {
                raw = new LivingEnderChestAccessor(server, ch, filterState,
                    transferredTargetSlots);
            }
        } else if (LivingItemManager.isLivingItem(stack)) {
            return null;
        } else {
            raw = new PlainSlotAccessor(containerCtx, slot, transferredTargetSlots);
        }

        return new FilteredSlotAccessor(raw, filterState);
    }

    public static SlotAccessor createForNeighbor(IItemHandler handler, int slot,
                                                  ComponentState filterState) {
        SlotAccessor raw = new NeighborSlotAccessor(handler, slot);
        return new FilteredSlotAccessor(raw, filterState);
    }
}