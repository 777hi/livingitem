package com.qiqi.li.living.domain.redstone.piston;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class PistonChainResolverTest {

    @ParameterizedTest
    @CsvSource({"0,-1,12,7,2", "0,1,12,17,22", "-1,0,12,11,10", "1,0,12,13,14"})
    void resolvesFourDirectionsByValue(int dx, int dy, int host, int first, int destination) {
        var ctx = new FakeContainerContext(25, 5).set(first, new ItemStack(Items.DIRT, 7));
        var push = assertInstanceOf(PistonPlan.Push.class,
            PistonChainResolver.resolve(ctx, host, new Pos2D(dx, dy), 1));
        assertEquals(List.of(first), push.cargoSlots());
        assertEquals(destination, push.destinationSlot());
    }

    @Test
    void emptyFrontIsSuccessfulPlanAndDoesNotReachDistantCargo() {
        var ctx = new FakeContainerContext(9, 9).set(2, new ItemStack(Items.DIRT));
        assertInstanceOf(PistonPlan.EmptyPush.class,
            PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
    }

    @Test
    void firstGapEndsShortChain() {
        var ctx = new FakeContainerContext(9, 9)
            .set(1, new ItemStack(Items.DIRT, 64)).set(3, new ItemStack(Items.IRON_INGOT));
        var push = assertInstanceOf(PistonPlan.Push.class,
            PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
        assertEquals(List.of(1), push.cargoSlots());
        assertEquals(2, push.destinationSlot());
    }

    @Test
    void forceCountsSlotsNotItems() {
        var ctx = new FakeContainerContext(9, 9)
            .set(1, new ItemStack(Items.DIRT, 64)).set(2, new ItemStack(Items.IRON_INGOT, 1));
        var push = assertInstanceOf(PistonPlan.Push.class,
            PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 2));
        assertEquals(List.of(1, 2), push.cargoSlots());
    }

    @Test
    void oversizedOrdinaryChainIsBlocked() {
        var ctx = new FakeContainerContext(9, 9)
            .set(1, new ItemStack(Items.DIRT)).set(2, new ItemStack(Items.IRON_INGOT));
        assertEquals(PistonPlan.Reason.CHAIN_TOO_LONG,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 1)).reason());
    }

    @Test
    void hoppersInsideForceRangeAreCargoAndOnlyNextSlotIsReceiverCandidate() {
        ItemStack cargoHopper = new ItemStack(Items.HOPPER, 3);
        ItemStack receiverHopper = new ItemStack(Items.HOPPER, 2);
        LivingItemManager.setLiving(cargoHopper, true);
        LivingItemManager.setLiving(receiverHopper, true);
        var ctx = new FakeContainerContext(9, 9).set(1, new ItemStack(Items.DIRT))
            .set(2, new ItemStack(Items.IRON_INGOT, 7)).set(3, cargoHopper).set(4, receiverHopper);
        var receiver = assertInstanceOf(PistonPlan.Receiver.class,
            PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 3));
        assertEquals(List.of(1, 2, 3), receiver.cargoSlots());
        assertEquals(4, receiver.receiverSlot());
        var nearer = assertInstanceOf(PistonPlan.Receiver.class,
            PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 2));
        assertEquals(3, nearer.receiverSlot());
    }

    @Test
    void unactivatedHopperDoesNotProvideReceiverCandidate() {
        var ctx = new FakeContainerContext(9, 9).set(1, new ItemStack(Items.DIRT))
            .set(2, new ItemStack(Items.HOPPER));
        assertInstanceOf(PistonPlan.Blocked.class,
            PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 1));
    }

    @ParameterizedTest
    @CsvSource({"4,1,0", "0,-1,0", "20,0,1", "0,0,-1"})
    void headOutsideContainerIsBlocked(int host, int dx, int dy) {
        var ctx = new FakeContainerContext(25, 5);
        assertEquals(PistonPlan.Reason.BOUNDARY,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, host, new Pos2D(dx, dy), 1)).reason());
    }

    @Test
    void fullChainAtBoundaryCannotPushOutside() {
        var ctx = new FakeContainerContext(3, 3).set(1, new ItemStack(Items.DIRT))
            .set(2, new ItemStack(Items.IRON_INGOT));
        assertEquals(PistonPlan.Reason.BOUNDARY,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 2)).reason());
    }

    @Test
    void invalidArgumentsDoNotParse() {
        var ctx = new FakeContainerContext(9, 9);
        assertEquals(PistonPlan.Reason.INVALID_ADDRESS,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, -1, Pos2D.RIGHT, 1)).reason());
        assertEquals(PistonPlan.Reason.INVALID_DIRECTION,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, 0, Pos2D.UP_RIGHT, 1)).reason());
        assertEquals(PistonPlan.Reason.INVALID_FORCE,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 0)).reason());
    }

    @Test
    void parserReadsOnlyForceRangeAndOneCandidate() {
        List<Integer> reads = new ArrayList<>();
        var ctx = new FakeContainerContext(9, 9) {
            @Override public ItemStack getItem(int slot) {
                reads.add(slot);
                return super.getItem(slot);
            }
        };
        ctx.set(1, new ItemStack(Items.DIRT)).set(2, new ItemStack(Items.IRON_INGOT))
            .set(3, new ItemStack(Items.GOLD_INGOT));
        PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 2);
        assertEquals(List.of(1, 2, 3), reads);
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void invalidWidthStopsBeforeReadingOrMovingCargo() {
        var ctx = new FakeContainerContext(9, 0) {
            @Override public ItemStack getItem(int slot) {
                throw new AssertionError("invalid layout must not read cargo");
            }
        };
        assertEquals(PistonPlan.Reason.INVALID_ADDRESS,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 1)).reason());
        assertEquals(PistonActions.Status.BLOCKED, PistonActions.pull(ctx, 0, Pos2D.RIGHT).status());
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void missingCellInIncompleteLastRowCannotBeDestination() {
        var ctx = new FakeContainerContext(7, 5).set(6, new ItemStack(Items.DIRT, 7));
        assertEquals(PistonPlan.Reason.BOUNDARY,
            assertInstanceOf(PistonPlan.Blocked.class,
                PistonChainResolver.resolve(ctx, 5, Pos2D.RIGHT, 1)).reason());
        assertEquals(PistonActions.Status.BLOCKED, PistonActions.pull(ctx, 5, Pos2D.RIGHT).status());
        assertEquals(7, ctx.getItem(6).getCount());
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void planCargoListIsImmutable() {
        var input = new ArrayList<>(List.of(1, 2));
        var push = new PistonPlan.Push(input, 3);
        input.clear();
        assertEquals(List.of(1, 2), push.cargoSlots());
        assertThrows(UnsupportedOperationException.class, () -> push.cargoSlots().add(4));
    }
}
