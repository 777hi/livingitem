package com.qiqi.li.living.domain.maid;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.MoveResult;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

class LivingSoulSlabFunctionTest {
    private static class Context extends SimpleContainerContext {
        boolean writable = true;
        int commits;

        Context(ItemStackHandler handler) {
            super(handler, null, List.of(), List.of(), null, "maid_test");
        }
        @Override public int getLiveColumns() { return 3; }
        @Override public void syncSlotToClients(int slot, ItemStack stack) {}
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

    private static LivingSoulSlabFunction function() {
        return new LivingSoulSlabFunction(stack -> stack.is(Items.PAPER));
    }

    private static ServerLevel level(long time, RandomSource random) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(time);
        when(level.getRandom()).thenReturn(random);
        return level;
    }

    private static void tick(Context context, long time, RandomSource random) {
        var function = function();
        for (int slot = 0; slot < context.getSize(); slot++) {
            ItemStack stack = context.getItem(slot);
            if (function.canApply(stack)) {
                function.tick(List.of(new SlotEntry(slot, stack)), context,
                        new TickContext(context), level(time, random));
                return;
            }
        }
    }

    private static RandomSource random(int intervalRoll, int direction) {
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(7)).thenReturn(intervalRoll);
        when(random.nextInt(6)).thenReturn(intervalRoll);
        when(random.nextInt(8)).thenReturn(intervalRoll);
        when(random.nextInt(1)).thenReturn(0);
        when(random.nextInt(49)).thenReturn(intervalRoll);
        when(random.nextInt(4)).thenReturn(direction);
        return random;
    }

    @Test void missingClockInitializesWithoutRollingOnSameTick() {
        ItemStackHandler handler = new ItemStackHandler(9);
        handler.setStackInSlot(4, soul());
        Context context = new Context(handler);
        RandomSource random = mock(RandomSource.class);
        tick(context, 20, random);
        assertInterval(context.getItem(4), 7);
        verifyNoInteractions(random);
    }

    @Test void onlyWholeSecondBoundariesRoll() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        RandomSource random = mock(RandomSource.class);
        tick(context, 19, random);
        tick(context, 21, random);
        verifyNoInteractions(random);
        assertInterval(context.getItem(4), 7);
    }

    @Test void missedProbabilityRollDoesNotMoveOrWrite() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        int baseline = context.commits;
        RandomSource random = random(1, ContainerContext.E_RIGHT);
        tick(context, 20, random);
        assertFalse(context.getItem(4).isEmpty());
        assertInterval(context.getItem(4), 7);
        assertEquals(baseline, context.commits);
        verify(random).nextInt(7);
        verify(random, never()).nextInt(4);
    }

    @Test void hitProbabilityAndSuccessfulMoveDecreasesInterval() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        tick(context, 20, random(0, ContainerContext.E_RIGHT));
        assertTrue(context.getItem(4).isEmpty());
        assertInterval(context.getItem(5), 6);
    }

    @Test void blockedTargetIncreasesIntervalWithoutReroll() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(4, stack);
        handler.setStackInSlot(5, new ItemStack(Items.STONE));
        Context context = new Context(handler);
        RandomSource random = random(0, ContainerContext.E_RIGHT);
        tick(context, 20, random);
        assertInterval(context.getItem(4), 8);
        verify(random).nextInt(7);
        verify(random).nextInt(4);
        verifyNoMoreInteractions(random);
    }

    @Test void boundaryDirectionIsFailureEvenWithAnotherOpenNeighbor() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(0, stack);
        Context context = new Context(handler);
        tick(context, 20, random(0, ContainerContext.E_LEFT));
        assertInterval(context.getItem(0), 8);
        assertTrue(context.getItem(1).isEmpty());
        assertTrue(context.getItem(3).isEmpty());
    }

    @Test void extractionRejectionIncreasesInterval() {
        ItemStackHandler handler = spy(new ItemStackHandler(9));
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(4, stack);
        doReturn(ItemStack.EMPTY).when(handler).extractItem(4, 1, true);
        Context context = new Context(handler);
        tick(context, 20, random(0, ContainerContext.E_RIGHT));
        assertInterval(context.getItem(4), 8);
    }

    @Test void intervalOneAttemptsEverySecond() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(1));
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        tick(context, 20, random(0, ContainerContext.E_RIGHT));
        assertTrue(context.getItem(5).is(Items.PAPER));
        assertInterval(context.getItem(5), 1);
    }

    @Test void repeatedFailuresClampAtFortyNine() {
        ItemStackHandler handler = new ItemStackHandler(1);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(48));
        handler.setStackInSlot(0, stack);
        Context context = new Context(handler);
        tick(context, 20, random(0, ContainerContext.E_LEFT));
        assertInterval(context.getItem(0), 49);
        tick(context, 40, random(0, ContainerContext.E_LEFT));
        assertInterval(context.getItem(0), 49);
    }

    @Test void failedResultWriteKeepsActualMoveAndOriginalInterval() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        context.writable = false;
        tick(context, 20, random(0, ContainerContext.E_RIGHT));
        assertTrue(context.getItem(4).isEmpty());
        assertTrue(context.getItem(5).is(Items.PAPER));
        assertInterval(context.getItem(5), 7);
    }

    @Test void manualSlotMoveKeepsInterval() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(17));
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        context.move(4, 5, 1);
        tick(context, 21, random(0, ContainerContext.E_LEFT));
        assertInterval(context.getItem(5), 17);
    }

    @Test void invalidStoredIntervalResetsToSevenWithoutRolling() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(50));
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        RandomSource random = mock(RandomSource.class);
        tick(context, 20, random);
        assertInterval(context.getItem(4), 7);
        verifyNoInteractions(random);
    }

    @Test void oversizedSoulDoesNotScheduleOrSplit() {
        ItemStackHandler handler = new ItemStackHandler(9);
        ItemStack stack = soul();
        stack.setCount(2);
        handler.setStackInSlot(4, stack);
        Context context = new Context(handler);
        tick(context, 20, random(0, ContainerContext.E_RIGHT));
        assertEquals(2, context.getItem(4).getCount());
        assertFalse(context.getItem(4).has(MaidComponents.CLOCK.get()));
    }

    @Test void sourceDisappearingAndBackendProblemsAreNeutral() {
        assertEquals(LivingSoulSlabFunction.AttemptOutcome.NEUTRAL,
                LivingSoulSlabFunction.classify(new MoveResult.Rejected(MoveResult.Reason.SOURCE_EMPTY)));
        assertEquals(LivingSoulSlabFunction.AttemptOutcome.NEUTRAL,
                LivingSoulSlabFunction.classify(new MoveResult.Unsupported(MoveResult.Reason.EXACT_WRITE_UNSUPPORTED)));
        assertEquals(LivingSoulSlabFunction.AttemptOutcome.NEUTRAL,
                LivingSoulSlabFunction.classify(new MoveResult.BackendViolation("test")));
    }

    @Test void ordinaryRejectionsAreFailures() {
        for (MoveResult.Reason reason : MoveResult.Reason.values()) {
            if (reason == MoveResult.Reason.SOURCE_EMPTY || reason == MoveResult.Reason.EXACT_WRITE_UNSUPPORTED) continue;
            assertEquals(LivingSoulSlabFunction.AttemptOutcome.FAILURE,
                    LivingSoulSlabFunction.classify(new MoveResult.Rejected(reason)));
        }
    }

    @Test void directionSelectionUsesFourWayRandom() {
        RandomSource random = RandomSource.create(42);
        boolean[] seen = new boolean[4];
        for (int i = 0; i < 1000; i++) seen[LivingSoulSlabFunction.chooseDirection(random)] = true;
        assertArrayEquals(new boolean[]{true, true, true, true}, seen);
    }

    private static void assertInterval(ItemStack stack, int expected) {
        LivingSoulSlabClock clock = stack.get(MaidComponents.CLOCK.get());
        assertNotNull(clock);
        assertEquals(expected, clock.intervalSeconds());
    }
}
