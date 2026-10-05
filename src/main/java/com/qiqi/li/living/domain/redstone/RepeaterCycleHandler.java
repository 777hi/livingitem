package com.qiqi.li.living.domain.redstone;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.interaction.InteractionHandler;

public class RepeaterCycleHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (stack.isEmpty()) return;

        if (LivingRepeaterFunction.cycleDelay(stack)) {
            player.containerMenu.broadcastChanges();
        }
    }
}