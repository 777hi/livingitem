package com.qiqi.li.client.render.tlm;

import com.qiqi.li.client.render.tlm.SoulSlabPreviewMoves.Position;
import com.qiqi.li.client.render.tlm.SoulSlabPreviewMoves.Snapshot;
import com.qiqi.li.living.domain.maid.LivingSoulSlabClock;
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
    void physicalAliasesIgnoreDisplayCoordinates() {
        Position a = position(5, 0, 0), b = position(5, 100, 80);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Position(new Object(), 5, 0, 0));
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
    void onlyOwnClockIsIgnoredWithoutMutatingStacks() {
        var dimension = ResourceLocation.withDefaultNamespace("overworld");
        ItemStack before = new ItemStack(Items.PAPER);
        before.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.start(dimension, 100, 20, 6));
        ItemStack after = before.copy();
        after.set(MaidComponents.CLOCK.get(), LivingSoulSlabClock.start(dimension, 460, 20, 6));
        assertFalse(ItemStack.matches(before, after));
        assertTrue(ItemStack.matches(SoulSlabPreviewMoves.stable(before), SoulSlabPreviewMoves.stable(after)));
        assertTrue(before.has(MaidComponents.CLOCK.get()));
        assertTrue(after.has(MaidComponents.CLOCK.get()));
        after.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("changed"));
        assertFalse(ItemStack.matches(SoulSlabPreviewMoves.stable(before), SoulSlabPreviewMoves.stable(after)));
    }
}
