package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.domain.redstone.LivingComparatorFunction;

public class ComparatorToggleHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (stack.isEmpty()) return;

        if (LivingComparatorFunction.toggleMode(stack)) {
            player.containerMenu.broadcastChanges();
        }
    }
}