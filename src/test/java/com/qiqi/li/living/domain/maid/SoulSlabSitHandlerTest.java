package com.qiqi.li.living.domain.maid;

import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoulSlabSitHandlerTest {
    @Test void validSoulTogglesAndMarksSlotChanged() {
        ItemStack soul = livingSoul(1);
        ServerPlayer player = mock(ServerPlayer.class);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu;
        Slot slot = spy(new Slot(new SimpleContainer(soul), 0, 0, 0));
        var handler = new SoulSlabSitHandler(stack -> true);

        handler.handle(player, slot);
        assertTrue(LivingSoulSlabFunction.isSitting(slot.getItem()));
        verify(slot).setChanged();
        verify(menu).broadcastChanges();

        handler.handle(player, slot);
        assertFalse(LivingSoulSlabFunction.isSitting(slot.getItem()));
        verify(slot, times(2)).setChanged();
        verify(menu, times(2)).broadcastChanges();
    }

    @Test void invalidTargetsDoNothing() {
        ServerPlayer player = mock(ServerPlayer.class);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu;
        var handler = new SoulSlabSitHandler(stack -> true);

        for (ItemStack stack : new ItemStack[]{
                new ItemStack(Items.PAPER), livingSoul(2), new ItemStack(Items.STONE)}) {
            Slot slot = spy(new Slot(new SimpleContainer(stack), 0, 0, 0));
            handler.handle(player, slot);
            assertFalse(LivingSoulSlabFunction.isSitting(slot.getItem()));
            verify(slot, never()).setChanged();
        }
        verify(menu, never()).broadcastChanges();
    }

    @Test void missingMaidSnapshotDoesNothing() {
        ItemStack soul = livingSoul(1);
        ServerPlayer player = mock(ServerPlayer.class);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu;
        Slot slot = spy(new Slot(new SimpleContainer(soul), 0, 0, 0));

        new SoulSlabSitHandler(stack -> false).handle(player, slot);

        assertFalse(LivingSoulSlabFunction.isSitting(slot.getItem()));
        verify(slot, never()).setChanged();
        verify(menu, never()).broadcastChanges();
    }

    private static ItemStack livingSoul(int count) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }
}
