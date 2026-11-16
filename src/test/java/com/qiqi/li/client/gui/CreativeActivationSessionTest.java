package com.qiqi.li.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class CreativeActivationSessionTest {
    @Test
    void oneRequestAtATime_andUnlockAfterMatchingResult() {
        var session = new CreativeActivationSession(0);
        ItemStack carried = new ItemStack(Items.STONE, 3);
        var request = session.begin(carried, new CompoundTag());
        assertNotNull(request);
        assertTrue(session.pending());
        assertNull(session.begin(carried, new CompoundTag()));
        assertTrue(session.accept(0, request.requestId(), carried.copy()));
        assertFalse(session.pending());
        assertFalse(session.accept(0, request.requestId(), carried));
        var next = session.begin(carried, new CompoundTag());
        assertNotEquals(request.requestId(), next.requestId());
    }

    @Test
    void wrongMenuOrRequestCannotUnlockOrAccept() {
        var session = new CreativeActivationSession(0);
        ItemStack carried = new ItemStack(Items.STONE);
        var request = session.begin(carried, new CompoundTag());
        assertFalse(session.accept(1, request.requestId(), carried));
        assertFalse(session.accept(0, request.requestId() + 1, carried));
        assertTrue(session.pending());
        assertTrue(session.accept(0, request.requestId(), carried));
    }

    @Test
    void reopenedSameMenuRejectsOldResult() {
        ItemStack carried = new ItemStack(Items.STONE);
        var old = new CreativeActivationSession(0);
        var request = old.begin(carried, new CompoundTag());
        old.clear();
        var reopened = new CreativeActivationSession(0);
        var next = reopened.begin(carried, new CompoundTag());
        assertFalse(reopened.accept(0, request.requestId(), carried));
        assertTrue(reopened.pending());
        assertTrue(reopened.accept(0, next.requestId(), carried));
    }

    @Test
    void changedCursorIsNotOverwritten_andMatchingReplyEndsWait() {
        var session = new CreativeActivationSession(0);
        ItemStack carried = new ItemStack(Items.STONE, 3);
        var request = session.begin(carried, new CompoundTag());
        carried.shrink(1);
        assertFalse(session.accept(0, request.requestId(), carried));
        assertFalse(session.pending());
    }

    @Test
    void forcedRemovalDiscardsOnlyTheUnresolvedExpectedCursor() {
        var session = new CreativeActivationSession(0);
        ItemStack carried = new ItemStack(Items.STONE);
        session.begin(carried, new CompoundTag());
        assertTrue(session.discardExpected(carried));
        assertFalse(session.pending());
        session.begin(carried, new CompoundTag());
        assertFalse(session.discardExpected(new ItemStack(Items.DIRT)));
        assertFalse(session.pending());
    }

    @Test
    void emptyOrMissingCursorDoesNotBegin() {
        var session = new CreativeActivationSession(0);
        assertNull(session.begin(ItemStack.EMPTY, new CompoundTag()));
        assertNull(session.begin(new ItemStack(Items.STONE), null));
        assertFalse(session.pending());
    }
}
