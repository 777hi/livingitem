package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabAmbientScheduleTest {
    @Test
    void comparisonUsesValueBeforeIncrement() {
        var schedule = new SoulSlabAmbientSchedule();
        assertFalse(schedule.tick(0));
        assertEquals(1, schedule.time());
        assertTrue(schedule.tick(0));
        assertEquals(-120, schedule.time());
    }

    @Test
    void resetRestoresFirstAttemptState() {
        var schedule = new SoulSlabAmbientSchedule();
        schedule.tick(0);
        schedule.tick(0);
        schedule.reset();
        assertEquals(0, schedule.time());
        assertFalse(schedule.tick(0));
    }

    @Test
    void negativeCooldownCannotTrigger() {
        var schedule = new SoulSlabAmbientSchedule();
        schedule.tick(0);
        schedule.tick(0);
        for (int i = 0; i < 120; i++) assertFalse(schedule.tick(0));
        assertEquals(0, schedule.time());
    }
}
