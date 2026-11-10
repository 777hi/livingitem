package com.qiqi.li.living.compat.tlm;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingMaidFavorTest {
    private static CompoundTag snapshot(int favor) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(PendingMaidFavor.FAVOR_KEY, favor);
        tag.putString("unknown_addon_data", "keep");
        return tag;
    }

    @Test void missingPendingReadsZero() {
        assertEquals(0, PendingMaidFavor.read(snapshot(10)));
    }

    @Test void addPreservesUnknownFieldsAndCapsTotalAtMaximum() {
        CompoundTag tag = snapshot(382);
        assertTrue(PendingMaidFavor.add(tag, 10));
        assertEquals(2, PendingMaidFavor.read(tag));
        assertEquals("keep", tag.getString("unknown_addon_data"));
        assertFalse(PendingMaidFavor.add(tag, 1));
    }

    @Test void corruptFavorRejectsWithoutRepairingSnapshot() {
        for (int favor : new int[]{-1, 385, Integer.MAX_VALUE}) {
            CompoundTag tag = snapshot(favor);
            assertFalse(PendingMaidFavor.add(tag, 1));
            assertFalse(tag.contains(PendingMaidFavor.KEY));
            assertEquals(favor, tag.getInt(PendingMaidFavor.FAVOR_KEY));
        }
    }

    @Test void wrongTypesAndNegativePendingRejectWithoutRepair() {
        CompoundTag wrong = snapshot(10);
        wrong.putString(PendingMaidFavor.KEY, "12");
        assertEquals(0, PendingMaidFavor.read(wrong));
        assertFalse(PendingMaidFavor.add(wrong, 1));
        assertTrue(wrong.contains(PendingMaidFavor.KEY, net.minecraft.nbt.Tag.TAG_STRING));
        CompoundTag negative = snapshot(10);
        negative.putInt(PendingMaidFavor.KEY, -9);
        assertEquals(0, PendingMaidFavor.read(negative));
        assertFalse(PendingMaidFavor.add(negative, 1));
        assertEquals(-9, negative.getInt(PendingMaidFavor.KEY));
    }

    @Test void hugeDeltaCannotOverflow() {
        CompoundTag tag = snapshot(0);
        assertTrue(PendingMaidFavor.add(tag, Integer.MAX_VALUE));
        assertEquals(384, PendingMaidFavor.read(tag));
    }

    @Test void takeReturnsOnceAndRemovesOnlyOwnKey() {
        CompoundTag tag = snapshot(20);
        tag.putInt(PendingMaidFavor.KEY, 7);
        assertEquals(7, PendingMaidFavor.take(tag));
        assertEquals(0, PendingMaidFavor.take(tag));
        assertEquals(20, tag.getInt(PendingMaidFavor.FAVOR_KEY));
        assertEquals("keep", tag.getString("unknown_addon_data"));
    }
}
