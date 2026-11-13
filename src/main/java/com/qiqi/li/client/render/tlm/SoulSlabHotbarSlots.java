package com.qiqi.li.client.render.tlm;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** 原版HUD快捷栏调用的真实玩家槽分类。 */
final class SoulSlabHotbarSlots {
    static final int OFFHAND_SLOT = Inventory.SLOT_OFFHAND;

    private SoulSlabHotbarSlots() {}

    static int inventorySlot(Player player, ItemStack rendered, int seed) {
        if (player == null || rendered == null || rendered.isEmpty()) return -1;
        if (seed >= 1 && seed <= 9) {
            int slot = seed - 1;
            return player.getInventory().getItem(slot) == rendered ? slot : -1;
        }
        if (seed == 10 && player.getOffhandItem() == rendered) return OFFHAND_SLOT;
        return -1;
    }
}
