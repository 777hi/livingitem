package com.qiqi.li.living.domain.redstone;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活拉杆的交互处理器。
 *
 * <p>玩家点击装有活拉杆的槽位时触发，调用 {@link LivingLeverFunction#toggleLever} 切换拉杆的
 * 开 / 关（powered）状态，从而改变其对外的红石信号。</p>
 */
public class LeverToggleHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (stack.isEmpty()) return;

        if (LivingLeverFunction.toggleLever(stack)) {
            player.containerMenu.broadcastChanges();
        }
    }
}