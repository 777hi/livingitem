package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static com.qiqi.li.client.render.tlm.SoulSlabPreviewSurface.Kind.*;
import static com.qiqi.li.client.render.tlm.SoulSlabPreviewSurface.Motion.*;
import static org.junit.jupiter.api.Assertions.*;

class SoulSlabPreviewSurfaceTest {
    @Test
    void onlyNonContainerScreensBelongToHudSurface() {
        assertTrue(SoulSlabPreviewSurface.hudOwnsFrame(false));
        assertFalse(SoulSlabPreviewSurface.hudOwnsFrame(true));
    }

    @Test
    void everyCrossSurfaceTransitionSettles() {
        for (var source : SoulSlabPreviewSurface.Kind.values()) {
            for (var target : SoulSlabPreviewSurface.Kind.values()) {
                if (source != target) {
                    assertEquals(SETTLE, SoulSlabPreviewSurface.transition(source, target, true));
                    assertEquals(SETTLE, SoulSlabPreviewSurface.transition(source, target, false));
                }
            }
        }
    }

    @Test
    void sameSurfaceDistinguishesStationaryWalkingAndCursor() {
        assertEquals(NONE, SoulSlabPreviewSurface.transition(HUD_SLOT, HUD_SLOT, true));
        assertEquals(NONE, SoulSlabPreviewSurface.transition(GUI_SLOT, GUI_SLOT, true));
        assertEquals(NONE, SoulSlabPreviewSurface.transition(CURSOR, CURSOR, true));
        assertEquals(SETTLE, SoulSlabPreviewSurface.transition(HUD_SLOT, HUD_SLOT, false));
        assertEquals(MOVE, SoulSlabPreviewSurface.transition(GUI_SLOT, GUI_SLOT, false));
        assertEquals(SETTLE, SoulSlabPreviewSurface.transition(CURSOR, CURSOR, false));
    }
}
