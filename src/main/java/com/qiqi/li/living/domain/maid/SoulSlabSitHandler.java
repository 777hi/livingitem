package com.qiqi.li.living.domain.maid;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import com.qiqi.li.living.interaction.InteractionHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** 空光标右键活魂符：toggle 坐下 / 起身（坐下 = 随机走位暂停）。 */
public class SoulSlabSitHandler implements InteractionHandler {
    private final Predicate<ItemStack> hasStoredMaid;

    public SoulSlabSitHandler() {
        this(TlmSoulData::hasStoredMaid);
    }

    SoulSlabSitHandler(Predicate<ItemStack> hasStoredMaid) {
        this.hasStoredMaid = hasStoredMaid;
    }

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack stack = targetSlot.getItem();
        if (!canToggle(stack)) return;

        if (LivingSoulSlabFunction.toggleSitting(stack)) {
            // ⚠️ 就地改组件必须经槽位标脏（同 ButtonPressHandler / IgniteHandler 先例）
            targetSlot.setChanged();
            player.containerMenu.broadcastChanges();
        }
    }

    /** 只有"活化 + 有女仆快照 + 单件"的魂符可以坐下；其余右键保持原版行为。 */
    private boolean canToggle(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1
                && LivingItemManager.isLivingItem(stack)
                && hasStoredMaid.test(stack);
    }
}
