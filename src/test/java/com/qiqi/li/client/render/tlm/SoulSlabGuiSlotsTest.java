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

    @Test
    void hoverRequiresExactDisplayedSlotIdentity() {
        SimpleContainer container = new SimpleContainer(new ItemStack(Items.PAPER));
        Slot display = new Slot(container, 0, 18, 18);
        assertTrue(SoulSlabGuiSlots.isHovered(display, display));

        Slot alias = new Slot(container, 0, 18, 18);
        assertFalse(SoulSlabGuiSlots.isHovered(display, alias),
                "同容器同索引同坐标的另一个显示对象也不冒充本槽");
        assertFalse(SoulSlabGuiSlots.isHovered(display, null));
        assertFalse(SoulSlabGuiSlots.isHovered(null, display));
    }

    @Test
    void onlyExactCarriedReferenceCanUseFloatingPreview() {
        ItemStack carried = new ItemStack(Items.PAPER);
        assertTrue(SoulSlabPreviewCache.isActualCarried(carried, carried, false));
        assertFalse(SoulSlabPreviewCache.isActualCarried(carried.copy(), carried, false));
        assertFalse(SoulSlabPreviewCache.isActualCarried(carried, carried, true));
        assertFalse(SoulSlabPreviewCache.isActualCarried(ItemStack.EMPTY, ItemStack.EMPTY, false));
        assertFalse(SoulSlabPreviewCache.isActualCarried(null, carried, false));
    }

    private static StackCopySlot copySlot() {
        return new StackCopySlot(0, 0) {
            @Override protected ItemStack getStackCopy() { return new ItemStack(Items.PAPER); }
            @Override protected void setStackCopy(ItemStack stack) {}
        };
    }
}
