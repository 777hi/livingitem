package com.qiqi.li.living.domain.redstone;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活比较器的交互处理器。
 *
 * <p>玩家点击装有活比较器的槽位时触发，切换比较器的模式（比较 / 减法）。</p>
 */
public class ComparatorToggleHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (stack.isEmpty()) return;

        if (LivingComparatorFunction.toggleMode(stack)) {
            // ⚠️ 就地改组件必须经槽位标脏（同 IgniteHandler，2026-10-27 审查 P2）
            targetSlot.setChanged();
            player.containerMenu.broadcastChanges();
        }
    }
}