package com.qiqi.li.living.domain.redstone;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活中继器的交互处理器。
 *
 * <p>玩家点击装有活中继器的槽位时触发，调用 {@link LivingRepeaterFunction#cycleDelay} 循环切换延迟档位
 * （1 → 2 → 3 → 4 → 1），切换成功后同步客户端界面。</p>
 */
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