package com.qiqi.li.living.domain.redstone;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活按钮的交互处理器。
 *
 * <p>当玩家点击容器内装有活按钮的槽位时触发，调用 {@link LivingButtonFunction#pressButton}
 * 切换按钮的按下 / 弹起状态，从而改变对外输出的红石信号。</p>
 */
public class ButtonPressHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (stack.isEmpty()) return;

        if (LivingButtonFunction.pressButton(stack)) {
            player.containerMenu.broadcastChanges();
        }
    }
}