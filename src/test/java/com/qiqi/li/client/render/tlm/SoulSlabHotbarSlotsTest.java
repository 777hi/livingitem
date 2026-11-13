package com.qiqi.li.client.render.tlm;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoulSlabHotbarSlotsTest {
    @Test
    void hotbarSeedsMapToMatchingRealReferences() {
        Player player = mock(Player.class);
        var inventory = new net.minecraft.world.entity.player.Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = new ItemStack(Items.PAPER);
            inventory.setItem(slot, stack);
            assertEquals(slot, SoulSlabHotbarSlots.inventorySlot(player, stack, slot + 1));
            assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, stack.copy(), slot + 1));
        }
    }

    @Test
    void seedTenOnlyAcceptsExactOffhandReference() {
        Player player = mock(Player.class);
        var inventory = new net.minecraft.world.entity.player.Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack offhand = new ItemStack(Items.PAPER);
        when(player.getOffhandItem()).thenReturn(offhand);
        assertEquals(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                SoulSlabHotbarSlots.inventorySlot(player, offhand, 10));
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, offhand.copy(), 10));
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, offhand, 9));
    }

    @Test
    void emptyNullAndOutOfRangeSeedsAreRejected() {
        Player player = mock(Player.class);
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(null, new ItemStack(Items.PAPER), 1));
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, null, 1));
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, ItemStack.EMPTY, 1));
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, new ItemStack(Items.PAPER), 0));
        assertEquals(-1, SoulSlabHotbarSlots.inventorySlot(player, new ItemStack(Items.PAPER), 11));
    }
}
