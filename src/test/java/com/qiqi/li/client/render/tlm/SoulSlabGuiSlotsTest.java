package com.qiqi.li.client.render.tlm;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.StackCopySlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabGuiSlotsTest {
    @Test
    void ordinarySlotHasStableReference() {
        Slot slot = new Slot(new SimpleContainer(new ItemStack(Items.PAPER)), 0, 0, 0);
        assertTrue(SoulSlabGuiSlots.hasStableStackReference(slot, slot));
        assertSame(slot.getItem(), slot.getItem());
    }

    @Test
    void officialCopySlotSafelyKeepsOriginalIcon() {
        StackCopySlot slot = copySlot();
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(slot, slot));
        assertNotSame(slot.getItem(), slot.getItem());
    }

    @Test
    void wrappedCopySlotUsesPhysicalSlotStability() {
        Slot display = new Slot(new SimpleContainer(0), 0, 0, 0);
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(display, copySlot()));
    }

    @Test
    void missingDisplayOrPhysicalSlotIsRejected() {
        Slot slot = new Slot(new SimpleContainer(0), 0, 0, 0);
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(null, slot));
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(slot, null));
    }

    private static StackCopySlot copySlot() {
        return new StackCopySlot(0, 0) {
            @Override protected ItemStack getStackCopy() { return new ItemStack(Items.PAPER); }
            @Override protected void setStackCopy(ItemStack stack) {}
        };
    }
}
