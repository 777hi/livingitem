package com.qiqi.li.living.domain.redstone.piston;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.MoveResult;
import com.qiqi.li.living.container.UndoToken;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class PistonActionsTest {

    private static class TrackingContext extends FakeContainerContext {
        final List<String> calls = new ArrayList<>();
        int rejectedSource = -1;
        MoveResult forcedFailure;
        boolean rejectUndo;

        TrackingContext() {
            super(9, 9);
        }

        @Override public int getSlotLimit(int slot) { return 512; }

        @Override
        public int simulateInsertItem(int slot, ItemStack stack) {
            return getItem(slot).isEmpty() ? Math.min(getSlotLimit(slot), stack.getCount()) : 0;
        }

        @Override
        public MoveResult moveStack(int from, int to) {
            calls.add("move:" + from + ":" + to);
            if (from == rejectedSource) {
                return forcedFailure != null ? forcedFailure
                    : new MoveResult.Rejected(MoveResult.Reason.TARGET_INSUFFICIENT);
            }
            return super.moveStack(from, to);
        }

        @Override
        public boolean undo(UndoToken token) {
            calls.add("undo:" + token.from() + ":" + token.to());
            return !rejectUndo && super.undo(token);
        }
    }

    private static long total(FakeContainerContext ctx) {
        long count = 0;
        for (int i = 0; i < ctx.getSize(); i++) count += ctx.getItem(i).getCount();
        return count;
    }

    private static TrackingContext threeCargo() {
        var ctx = new TrackingContext();
        ctx.set(1, new ItemStack(Items.DIRT, 7)).set(2, new ItemStack(Items.IRON_INGOT, 5))
            .set(3, new ItemStack(Items.GOLD_INGOT, 2));
        return ctx;
    }

    @Test
    void movesWholeChainTailToHeadUsingFramework() {
        var ctx = threeCargo();
        long before = total(ctx);
        var plan = PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3);
        assertTrue(PistonActions.push(ctx, plan).success());
        assertEquals(List.of("move:3:4", "move:2:3", "move:1:2"), ctx.calls);
        assertTrue(ctx.getItem(1).isEmpty());
        assertEquals(Items.DIRT, ctx.getItem(2).getItem());
        assertEquals(Items.IRON_INGOT, ctx.getItem(3).getItem());
        assertEquals(Items.GOLD_INGOT, ctx.getItem(4).getItem());
        assertEquals(before, total(ctx));
    }

    @Test
    void doesNotMergeAdjacentSameItemStacks() {
        var ctx = new TrackingContext();
        ctx.set(1, new ItemStack(Items.DIRT, 30)).set(2, new ItemStack(Items.DIRT, 40));
        assertTrue(PistonActions.push(ctx, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 2)).success());
        assertEquals(30, ctx.getItem(2).getCount());
        assertEquals(40, ctx.getItem(3).getCount());
        assertEquals(70, total(ctx));
    }

    @Test
    void preservesLivingItemComponentsAndOversizedQuantity() {
        var ctx = new TrackingContext();
        ItemStack stack = new ItemStack(Items.CHEST, 500);
        LivingItemManager.setLiving(stack, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("cargo"));
        ItemStack before = stack.copy();
        ctx.set(1, stack);
        assertTrue(PistonActions.push(ctx, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 1)).success());
        assertTrue(ItemStack.matches(before, ctx.getItem(2)));
        assertEquals(500, total(ctx));
    }

    @Test
    void rollsBackAllCompletedMovesInReverseOrder() {
        var ctx = threeCargo();
        ctx.rejectedSource = 1;
        var result = PistonActions.push(ctx, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
        assertEquals(PistonActions.Status.REJECTED, result.status());
        assertEquals(List.of("move:3:4", "move:2:3", "move:1:2", "undo:2:3", "undo:3:4"), ctx.calls);
        assertEquals(Items.DIRT, ctx.getItem(1).getItem());
        assertEquals(Items.IRON_INGOT, ctx.getItem(2).getItem());
        assertEquals(Items.GOLD_INGOT, ctx.getItem(3).getItem());
        assertTrue(ctx.getItem(4).isEmpty());
        assertEquals(14, total(ctx));
    }

    @Test
    void unsupportedFailureRemainsDistinctAfterUndo() {
        var ctx = threeCargo();
        ctx.rejectedSource = 2;
        ctx.forcedFailure = new MoveResult.Unsupported(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE);
        var result = PistonActions.push(ctx, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
        assertEquals(PistonActions.Status.UNSUPPORTED, result.status());
        assertInstanceOf(MoveResult.Unsupported.class, result.failure());
        assertEquals(List.of("move:3:4", "move:2:3", "undo:3:4"), ctx.calls);
        assertEquals(14, total(ctx));
    }

    @Test
    void reportsBackendViolationWithoutPretendingItWasNormalRejection() {
        var ctx = threeCargo();
        ctx.rejectedSource = 2;
        ctx.forcedFailure = new MoveResult.BackendViolation("test failure");
        var result = PistonActions.push(ctx, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
        assertEquals(PistonActions.Status.BACKEND_VIOLATION, result.status());
        assertFalse(result.success());
        assertTrue(ctx.getItem(4).isEmpty());
    }

    @Test
    void undoFailureIsNotReportedAsRestoredAndStopsFurtherUndo() {
        var ctx = threeCargo();
        ctx.rejectedSource = 1;
        ctx.rejectUndo = true;
        var result = PistonActions.push(ctx, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
        assertEquals(PistonActions.Status.UNDO_FAILED, result.status());
        assertFalse(result.success());
        assertEquals("undo:2:3", ctx.calls.getLast());
        assertEquals(14, total(ctx));
    }

    @Test
    void receiverCandidateIsNotMovedAndHasNoPressureFallback() {
        var ctx = threeCargo();
        var plan = new PistonPlan.Receiver(List.of(1, 2, 3), 4);
        assertEquals(PistonActions.Status.RECEIVER_PENDING, PistonActions.push(ctx, plan).status());
        assertTrue(ctx.calls.isEmpty());
        assertEquals(14, total(ctx));
    }

    @Test
    void emptyPushAndBlockedPlansDoNotInvokeMove() {
        var ctx = new TrackingContext();
        assertTrue(PistonActions.push(ctx, new PistonPlan.EmptyPush()).success());
        assertEquals(PistonActions.Status.BLOCKED,
            PistonActions.push(ctx, new PistonPlan.Blocked(PistonPlan.Reason.BOUNDARY)).status());
        assertTrue(ctx.calls.isEmpty());
    }

    @Test
    void stalePlanReadsCurrentSourceAndOccupiedDestination() {
        var ctx = new TrackingContext();
        ctx.set(1, new ItemStack(Items.DIRT));
        var plan = PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 1);
        ctx.set(1, new ItemStack(Items.GOLD_INGOT, 2));
        assertTrue(PistonActions.push(ctx, plan).success());
        assertEquals(Items.GOLD_INGOT, ctx.getItem(2).getItem());
        assertEquals(PistonActions.Status.REJECTED, PistonActions.push(ctx, plan).status());
    }

    @Test
    void stickyPullMovesOneWholeStackOnly() {
        var ctx = new TrackingContext();
        ctx.set(2, new ItemStack(Items.DIRT, 7)).set(3, new ItemStack(Items.IRON_INGOT));
        assertTrue(PistonActions.pull(ctx, 0, Pos2D.RIGHT).success());
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(ctx.getItem(2).isEmpty());
        assertEquals(Items.IRON_INGOT, ctx.getItem(3).getItem());
    }

    @Test
    void stickyPullCannotMergeIntoOccupiedHeadSlot() {
        var ctx = new TrackingContext();
        ctx.set(1, new ItemStack(Items.DIRT, 1)).set(2, new ItemStack(Items.DIRT, 7));
        assertEquals(PistonActions.Status.REJECTED, PistonActions.pull(ctx, 0, Pos2D.RIGHT).status());
        assertEquals(1, ctx.getItem(1).getCount());
        assertEquals(7, ctx.getItem(2).getCount());
    }

    @Test
    void firstSuccessfulStickyPullWinsWholeSharedSource() {
        var ctx = new TrackingContext();
        ctx.set(2, new ItemStack(Items.DIRT, 7));
        assertTrue(PistonActions.pull(ctx, 0, Pos2D.RIGHT).success());
        assertFalse(PistonActions.pull(ctx, 4, Pos2D.LEFT).success());
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(ctx.getItem(3).isEmpty());
        assertEquals(7, total(ctx));
    }

    @Test
    void failedFirstStickyPullDoesNotReserveCargo() {
        var ctx = new TrackingContext();
        ctx.set(1, new ItemStack(Items.GOLD_INGOT)).set(2, new ItemStack(Items.DIRT, 7));
        assertFalse(PistonActions.pull(ctx, 0, Pos2D.RIGHT).success());
        assertTrue(PistonActions.pull(ctx, 4, Pos2D.LEFT).success());
        assertEquals(7, ctx.getItem(3).getCount());
    }

    @Test
    void firstSuccessfulPullClaimsSharedDestinationWithoutMerging() {
        var ctx = new FakeContainerContext(25, 5)
            .set(13, new ItemStack(Items.DIRT, 7)).set(7, new ItemStack(Items.DIRT, 3));
        assertTrue(PistonActions.pull(ctx, 11, Pos2D.RIGHT).success());
        assertFalse(PistonActions.pull(ctx, 17, Pos2D.UP).success());
        assertEquals(7, ctx.getItem(12).getCount());
        assertEquals(3, ctx.getItem(7).getCount());
        assertEquals(10, total(ctx));
    }

    @Test
    void stickyPullAtBoundaryOrEmptySourceFails() {
        var ctx = new TrackingContext();
        assertFalse(PistonActions.pull(ctx, 0, Pos2D.LEFT).success());
        assertFalse(PistonActions.pull(ctx, 0, Pos2D.RIGHT).success());
        assertFalse(PistonActions.pull(ctx, 0, Pos2D.NONE).success());
    }
}
