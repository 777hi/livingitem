package com.qiqi.li.living.domain.battle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.battle.ReconcilePlan.BridgeRef;
import com.qiqi.li.living.domain.battle.ReconcilePlan.Plan;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class ReconcilePlanTest {

    private static Map<Integer, ItemStack> living(Object... slotStackPairs) {
        Map<Integer, ItemStack> out = new HashMap<>();
        for (int i = 0; i < slotStackPairs.length; i += 2) {
            out.put((Integer) slotStackPairs[i], (ItemStack) slotStackPairs[i + 1]);
        }
        return out;
    }

    @Test
    void emptyRosterAndEmptySlotsYieldsNoOps() {
        Plan plan = ReconcilePlan.diff(Map.of(), Map.of());
        assertTrue(plan.spawn().isEmpty());
        assertTrue(plan.discard().isEmpty());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void emptyRosterWithLivingSlotsSpawns() {
        Plan plan = ReconcilePlan.diff(Map.of(),
            living(4, new ItemStack(Items.STONE, 3)));
        assertEquals(java.util.List.of(4), plan.spawn());
        assertTrue(plan.discard().isEmpty());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void rosterSlotGoneDiscards() {
        Plan plan = ReconcilePlan.diff(
            Map.of(2, new BridgeRef(UUID.randomUUID(), new ItemStack(Items.STONE))),
            Map.of());
        assertEquals(java.util.List.of(2), plan.discard());
        assertTrue(plan.spawn().isEmpty());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void sameIdentityRelocationIsMove() {
        BridgeRef ref = new BridgeRef(UUID.randomUUID(), new ItemStack(Items.STONE));
        Plan plan = ReconcilePlan.diff(Map.of(2, ref),
            living(6, new ItemStack(Items.STONE, 3)));
        assertEquals(1, plan.moves().size());
        assertArrayEquals(new int[] {2, 6}, plan.moves().get(0));
        assertTrue(plan.spawn().isEmpty());
        assertTrue(plan.discard().isEmpty());
    }

    @Test
    void twoLivingItemsSwapSlotsProducesTwoMoves() {
        UUID furnace = UUID.randomUUID();
        UUID beacon = UUID.randomUUID();
        Plan plan = ReconcilePlan.diff(
            Map.of(0, new BridgeRef(furnace, new ItemStack(Items.FURNACE)),
                1, new BridgeRef(beacon, new ItemStack(Items.BEACON))),
            living(0, new ItemStack(Items.BEACON), 1, new ItemStack(Items.FURNACE)));
        assertEquals(2, plan.moves().size());
        // 身份配对：炉跟随炉、信标跟随信标，不是按槽位贪心交叉
        boolean furnaceMoved = false;
        boolean beaconMoved = false;
        for (int[] move : plan.moves()) {
            if (move[0] == 0 && move[1] == 1) furnaceMoved = true;
            if (move[0] == 1 && move[1] == 0) beaconMoved = true;
        }
        assertTrue(furnaceMoved, "炉应从 0 移到 1");
        assertTrue(beaconMoved, "信标应从 1 移到 0");
        assertTrue(plan.spawn().isEmpty());
        assertTrue(plan.discard().isEmpty());
    }

    @Test
    void differentIdentityIsDiscardPlusSpawn() {
        Plan plan = ReconcilePlan.diff(
            Map.of(2, new BridgeRef(UUID.randomUUID(), new ItemStack(Items.FURNACE))),
            living(2, new ItemStack(Items.BREWING_STAND)));
        assertEquals(java.util.List.of(2), plan.spawn());
        assertEquals(java.util.List.of(2), plan.discard());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void countChangeAloneProducesNoOps() {
        BridgeRef ref = new BridgeRef(UUID.randomUUID(), new ItemStack(Items.STONE, 1));
        Plan plan = ReconcilePlan.diff(Map.of(3, ref),
            living(3, new ItemStack(Items.STONE, 7)));
        assertTrue(plan.spawn().isEmpty());
        assertTrue(plan.discard().isEmpty());
        assertTrue(plan.moves().isEmpty());
    }
}
