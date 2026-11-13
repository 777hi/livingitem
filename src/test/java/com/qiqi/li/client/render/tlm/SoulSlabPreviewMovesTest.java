package com.qiqi.li.client.render.tlm;

import com.qiqi.li.client.render.tlm.SoulSlabPreviewMoves.Position;
import com.qiqi.li.client.render.tlm.SoulSlabPreviewMoves.Snapshot;
import com.qiqi.li.living.domain.maid.LivingSoulSlabClock;
import com.qiqi.li.living.domain.maid.LivingSoulSlabSitting;
import com.qiqi.li.living.domain.maid.MaidComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 不创建第三方实体的预览匹配守卫。 */
class SoulSlabPreviewMovesTest {
    private final Object owner = new Object();

    private Position position(int index, int x, int y) { return new Position(owner, index, x, y); }
    private Snapshot snapshot(Position position) { return new Snapshot(position, new ItemStack(Items.PAPER)); }

    @Test
    void uniqueAdjacentMoveKeepsSourceIdentity() {
        Position source = position(0, 0, 0), target = position(1, 18, 0);
        assertEquals(Map.of(target, source), SoulSlabPreviewMoves.match(List.of(snapshot(source)), List.of(snapshot(target))));
    }

    @Test
    void hotbarLogicalSpacingSupportsSnapshotFallback() {
        Object inventory = new Object();
        Position source = new Position(inventory, 0, 0, 0);
        Position target = new Position(inventory, 1, 20, 0);
        assertEquals(Map.of(target, source), SoulSlabPreviewMoves.match(
                List.of(snapshot(source)), List.of(snapshot(target))));
    }

    @Test
    void offhandNeedsLiveReferenceRatherThanSnapshotGuess() {
        Object inventory = new Object();
        Position hotbar = new Position(inventory, 8, 160, 0);
        Position offhand = new Position(inventory, net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, 200, 0);
        assertTrue(SoulSlabPreviewMoves.match(List.of(snapshot(hotbar)), List.of(snapshot(offhand))).isEmpty());
        ItemStack stack = new ItemStack(Items.PAPER);
        assertEquals(Map.of(offhand, hotbar), SoulSlabPreviewMoves.match(
                List.of(new Snapshot(hotbar, stack.copy(), stack)),
                List.of(new Snapshot(offhand, stack.copy(), stack))));
    }

    @Test
    void slotMovesToCursorWithoutAdjacency() {
        Position source = position(0, 0, 0), cursor = Position.cursor(new Object());
        assertEquals(Map.of(cursor, source), SoulSlabPreviewMoves.match(List.of(snapshot(source)), List.of(snapshot(cursor))));
    }

    @Test
    void cursorMovesToArbitrarySlot() {
        Object menu = new Object();
        Position cursor = Position.cursor(menu), target = new Position(new Object(), 99, 200, 120);
        assertEquals(Map.of(target, cursor), SoulSlabPreviewMoves.match(List.of(snapshot(cursor)), List.of(snapshot(target))));
    }

    @Test
    void liveReferenceCanMoveBetweenNonAdjacentOwners() {
        Position source = position(0, 0, 0);
        Position target = new Position(new Object(), 8, 144, 72);
        ItemStack stack = new ItemStack(Items.PAPER);
        assertEquals(Map.of(target, source), SoulSlabPreviewMoves.match(
                List.of(new Snapshot(source, stack.copy(), stack)),
                List.of(new Snapshot(target, stack.copy(), stack))));
    }

    @Test
    void unchangedPositionKeepsIdentity() {
        Position same = position(0, 0, 0);
        assertEquals(Map.of(same, same), SoulSlabPreviewMoves.match(List.of(snapshot(same)), List.of(snapshot(same))));
    }

    @Test
    void liveReferenceWinsBeforeIdenticalSnapshots() {
        Position left = position(0, 0, 0), right = position(2, 36, 0), cursor = Position.cursor(new Object());
        ItemStack first = new ItemStack(Items.PAPER), second = new ItemStack(Items.PAPER);
        List<Snapshot> removed = List.of(new Snapshot(left, first.copy(), first), new Snapshot(right, second.copy(), second));
        List<Snapshot> added = List.of(new Snapshot(cursor, first.copy(), first));
        assertEquals(Map.of(cursor, left), SoulSlabPreviewMoves.match(removed, added));
    }

    @Test
    void twoPossibleSourcesDoNotShareDestination() {
        Position left = position(0, 0, 0), right = position(2, 36, 0), target = position(1, 18, 0);
        assertTrue(SoulSlabPreviewMoves.match(List.of(snapshot(left), snapshot(right)), List.of(snapshot(target))).isEmpty());
    }

    @Test
    void twoPossibleDestinationsResetBoth() {
        Position source = position(1, 18, 0), left = position(0, 0, 0), right = position(2, 36, 0);
        assertTrue(SoulSlabPreviewMoves.match(List.of(snapshot(source)), List.of(snapshot(left), snapshot(right))).isEmpty());
    }

    @Test
    void twoIdenticalItemsStillHaveIndependentUniqueMoves() {
        Position source1 = position(0, 0, 0), source2 = position(6, 0, 44);
        Position target1 = position(1, 18, 0), target2 = position(7, 18, 44);
        assertEquals(Map.of(target1, source1, target2, source2), SoulSlabPreviewMoves.match(
                List.of(snapshot(source1), snapshot(source2)), List.of(snapshot(target1), snapshot(target2))));
    }

    @Test
    void identicalSnapshotsSwappedByReferenceFollowTheirStacks() {
        Position left = position(0, 0, 0), right = position(1, 18, 0);
        ItemStack a = new ItemStack(Items.PAPER), b = new ItemStack(Items.PAPER);
        List<Snapshot> removed = List.of(new Snapshot(left, a.copy(), a), new Snapshot(right, b.copy(), b));
        List<Snapshot> added = List.of(new Snapshot(left, b.copy(), b), new Snapshot(right, a.copy(), a));
        assertEquals(Map.of(left, right, right, left), SoulSlabPreviewMoves.match(removed, added));
    }

    @Test
    void adjacentSwapCanFormTwoWayCycleByReference() {
        Position left = position(0, 0, 0), right = position(1, 18, 0);
        ItemStack a = new ItemStack(Items.PAPER), b = new ItemStack(Items.MAP);
        List<Snapshot> removed = List.of(new Snapshot(left, a.copy(), a), new Snapshot(right, b.copy(), b));
        List<Snapshot> added = List.of(new Snapshot(left, b.copy(), b), new Snapshot(right, a.copy(), a));
        assertEquals(Map.of(left, right, right, left), SoulSlabPreviewMoves.match(removed, added));
    }

    @Test
    void physicalAliasesIgnoreDisplayCoordinates() {
        Position a = position(5, 0, 0), b = position(5, 100, 80);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Position(new Object(), 5, 0, 0));
    }

    @Test
    void cursorIdentityUsesMenuReference() {
        Object menu = new Object();
        assertEquals(Position.cursor(menu), Position.cursor(menu));
        assertNotEquals(Position.cursor(menu), Position.cursor(new Object()));
        assertTrue(Position.cursor(menu).isCursor());
        assertFalse(position(0, 0, 0).isCursor());
    }

    @Test
    void otherHostDiagonalAndNonAdjacentMovesReset() {
        Position source = position(0, 0, 0);
        List<Snapshot> targets = List.of(snapshot(new Position(new Object(), 1, 18, 0)),
                snapshot(position(2, 18, 18)), snapshot(position(3, 36, 0)));
        assertTrue(SoulSlabPreviewMoves.match(List.of(snapshot(source)), targets).isEmpty());
    }

    @Test
    void stableComponentsAndCountRemainSignificant() {
        Position source = position(0, 0, 0), target = position(1, 18, 0);
        ItemStack stack = new ItemStack(Items.PAPER);
        ItemStack changed = stack.copy();
        CompoundTag tag = new CompoundTag();
        tag.putString("model_id", "example:other");
        changed.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        assertTrue(SoulSlabPreviewMoves.match(List.of(new Snapshot(source, stack)), List.of(new Snapshot(target, changed))).isEmpty());
        assertTrue(SoulSlabPreviewMoves.match(List.of(new Snapshot(source, stack)), List.of(new Snapshot(target, stack.copyWithCount(2)))).isEmpty());
    }

    @Test
    void clockAndSittingAreIgnoredWithoutMutatingStacks() {
        ItemStack before = new ItemStack(Items.PAPER);
        before.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial());
        before.set(MaidComponents.SITTING.get(), new LivingSoulSlabSitting(false));
        ItemStack after = before.copy();
        after.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.initial().afterFailure());
        after.set(MaidComponents.SITTING.get(), new LivingSoulSlabSitting(true));
        assertFalse(ItemStack.matches(before, after));
        assertTrue(ItemStack.matches(SoulSlabPreviewMoves.stable(before), SoulSlabPreviewMoves.stable(after)));
        assertTrue(before.has(MaidComponents.CLOCK.get()));
        assertTrue(after.has(MaidComponents.SITTING.get()));
        after.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("changed"));
        assertFalse(ItemStack.matches(SoulSlabPreviewMoves.stable(before), SoulSlabPreviewMoves.stable(after)));
    }
}
