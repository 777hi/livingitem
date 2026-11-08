package com.qiqi.li.living.domain.maid;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

class LivingSoulSlabFunctionTest {
    private static class Context extends SimpleContainerContext {
        int syncs;
        int commits;
        boolean writable = true;

        Context(ItemStackHandler handler) {
            super(handler, null, List.of(), List.of(), null, "maid_test");
        }
        @Override public int getLiveColumns() { return 3; }
        @Override public void syncSlotToClients(int slot, ItemStack stack) { syncs++; }
        @Override public boolean commitItemComponents(int slot, ItemStack before, ItemStack after) {
            commits++;
            return writable && super.commitItemComponents(slot, before, after);
        }
    }

    private static ItemStack soul() {
        ItemStack stack = new ItemStack(Items.PAPER);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static LivingSoulSlabFunction function(double hunger, int capacity) {
        return new LivingSoulSlabFunction(stack -> stack.is(Items.PAPER),
                stack -> Optional.of(new LivingSoulSlabFunction.WalkInputs(hunger, capacity)));
    }

    private static ServerLevel level(long time) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(time);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getRandom()).thenReturn(RandomSource.create(1));
        return level;
    }

    private static void tick(LivingSoulSlabFunction function, Context context, long time) {
        for (int slot = 0; slot < context.getSize(); slot++) {
            ItemStack stack = context.getItem(slot);
            if (function.canApply(stack)) {
                function.tick(List.of(new SlotEntry(slot, stack)), context, new TickContext(context), level(time));
                return;
            }
        }
    }

    @Test void waitsForFirstIntervalThenMovesWholeItem() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        var function = function(20, 6);
        tick(function, context, 0);
        assertEquals(360, context.getItem(4).get(MaidComponents.CLOCK.get()).deadline());
        tick(function, context, 359);
        assertFalse(context.getItem(4).isEmpty());
        tick(function, context, 360);
        assertTrue(context.getItem(4).isEmpty());
        int items = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = context.getItem(slot);
            if (!stack.isEmpty()) {
                assertTrue(List.of(1, 3, 5, 7).contains(slot));
                assertEquals(1, stack.getCount());
                assertEquals(720, stack.get(MaidComponents.CLOCK.get()).deadline());
                items++;
            }
        }
        assertEquals(1, items);
    }

    @Test void fullContainerSkipsWithoutAccumulatingSteps() {
        ItemStackHandler handler = new ItemStackHandler(9);
        for (int slot = 0; slot < 9; slot++) handler.setStackInSlot(slot, new ItemStack(Items.STONE));
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        var function = function(20, 6);
        tick(function, context, 0);
        tick(function, context, 360);
        assertEquals(720, context.getItem(4).get(MaidComponents.CLOCK.get()).deadline());
        handler.setStackInSlot(5, ItemStack.EMPTY);
        tick(function, context, 361);
        assertFalse(context.getItem(4).isEmpty());
        tick(function, context, 720);
        assertTrue(context.getItem(4).isEmpty());
        assertTrue(context.getItem(5).is(Items.PAPER));
    }

    @Test void clockFollowsManualSlotChange() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        var function = function(20, 6);
        tick(function, context, 0);
        context.move(4, 0, 1);
        tick(function, context, 100);
        assertEquals(360, context.getItem(0).get(MaidComponents.CLOCK.get()).deadline());
    }

    @Test void zeroHungerStopsAndClearsActivityWithoutConsumingItem() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        tick(function(20, 6), context, 0);
        tick(function(0, 6), context, 50);
        assertFalse(context.getItem(4).has(MaidComponents.CLOCK.get()));
        assertEquals(1, context.getItem(4).getCount());
    }

    @Test void rejectedClockCommitDoesNotMove() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        var function = function(20, 6);
        tick(function, context, 0);
        context.writable = false;
        tick(function, context, 360);
        assertFalse(context.getItem(4).isEmpty());
        assertEquals(360, context.getItem(4).get(MaidComponents.CLOCK.get()).deadline());
    }

    @Test void overdueDoesNotImmediatelyCatchUp() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        var function = function(20, 6);
        tick(function, context, 0);
        tick(function, context, 10000);
        assertFalse(context.getItem(4).isEmpty());
        assertEquals(10360, context.getItem(4).get(MaidComponents.CLOCK.get()).deadline());
    }

    @Test void oversizedSoulDoesNotSplit() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.setCount(2);
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        tick(function(20, 6), context, 0);
        assertEquals(2, context.getItem(4).getCount());
        assertFalse(context.getItem(4).has(MaidComponents.CLOCK.get()));
    }

    @Test void missingInputsDoNotCreateClock() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        var function = new LivingSoulSlabFunction(stack -> stack.is(Items.PAPER), stack -> Optional.empty());
        tick(function, context, 0);
        assertFalse(context.getItem(4).has(MaidComponents.CLOCK.get()));
    }

    @Test void occupiedAndInvalidNeighborsAreNeverSelected() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(0, soul());
        handler.setStackInSlot(1, new ItemStack(Items.STONE));
        Context context = new Context(handler);
        for (int i = 0; i < 100; i++) {
            assertEquals(3, LivingSoulSlabFunction.chooseTarget(context, 0, context.getItem(0), RandomSource.create(i)));
        }
    }

    @Test void uniformSelectionIncludesEveryEmptyNeighbor() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        int[] counts = new int[9];
        RandomSource random = RandomSource.create(42);
        for (int i = 0; i < 10000; i++) {
            counts[LivingSoulSlabFunction.chooseTarget(context, 4, context.getItem(4), random)]++;
        }
        for (int slot : new int[]{1, 3, 5, 7}) assertTrue(counts[slot] > 2300 && counts[slot] < 2700);
    }
}
