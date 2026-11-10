package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabPreviewVisibilityTest {
    @Test
    void neverDrawnSentinelIsNotVisible() {
        assertFalse(SoulSlabPreviewVisibility.recentlyDrawn(0, Integer.MIN_VALUE));
    }

    @Test
    void onlyCurrentAndTwoPreviousTicksRemainVisible() {
        assertTrue(SoulSlabPreviewVisibility.recentlyDrawn(10, 10));
        assertTrue(SoulSlabPreviewVisibility.recentlyDrawn(10, 8));
        assertFalse(SoulSlabPreviewVisibility.recentlyDrawn(10, 7));
    }

    @Test
    void tickOverflowDoesNotTurnOldPreviewVisible() {
        assertFalse(SoulSlabPreviewVisibility.recentlyDrawn(Integer.MAX_VALUE, Integer.MIN_VALUE));
        assertFalse(SoulSlabPreviewVisibility.recentlyDrawn(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
}
