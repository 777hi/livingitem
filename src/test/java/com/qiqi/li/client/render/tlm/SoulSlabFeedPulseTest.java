package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabFeedPulseTest {
    @Test void inactivePulseHasUnitScale() {
        assertEquals(1, SoulSlabFeedPulse.scale(0, 0));
        assertEquals(1, SoulSlabFeedPulse.scale(-1, 0.5f));
    }

    @Test void pulseStartsAndEndsAtUnitScale() {
        assertEquals(1, SoulSlabFeedPulse.scale(SoulSlabFeedPulse.DURATION_TICKS, 0), 0.0001f);
        assertEquals(1, SoulSlabFeedPulse.scale(1, 1), 0.0001f);
    }

    @Test void pulsePeaksInMiddle() {
        float middle = SoulSlabFeedPulse.scale(3, 0);
        assertEquals(1.12f, middle, 0.0001f);
        assertTrue(middle > SoulSlabFeedPulse.scale(5, 0));
    }
}
