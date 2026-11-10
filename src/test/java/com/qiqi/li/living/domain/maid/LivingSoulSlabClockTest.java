package com.qiqi.li.living.domain.maid;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LivingSoulSlabClockTest {
    @Test void startsAtSevenSeconds() {
        assertEquals(7, LivingSoulSlabClock.initial().intervalSeconds());
    }

    @Test void successDecreasesAndFailureIncreasesByOne() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.initial();
        assertEquals(6, clock.afterSuccess().intervalSeconds());
        assertEquals(8, clock.afterFailure().intervalSeconds());
    }

    @Test void repeatedSuccessStopsAtOne() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.initial();
        for (int i = 0; i < 100; i++) clock = clock.afterSuccess();
        assertEquals(1, clock.intervalSeconds());
        assertEquals(1, clock.afterSuccess().intervalSeconds());
    }

    @Test void repeatedFailureStopsAtFortyNine() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.initial();
        for (int i = 0; i < 100; i++) clock = clock.afterFailure();
        assertEquals(49, clock.intervalSeconds());
        assertEquals(49, clock.afterFailure().intervalSeconds());
    }

    @Test void onlyOneThroughFortyNineAreValid() {
        assertFalse(new LivingSoulSlabClock(0).valid());
        assertTrue(new LivingSoulSlabClock(1).valid());
        assertTrue(new LivingSoulSlabClock(49).valid());
        assertFalse(new LivingSoulSlabClock(50).valid());
    }
}
