package com.qiqi.li.living.domain.maid;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.resources.ResourceLocation;

class LivingSoulSlabClockTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");

    @ParameterizedTest
    @CsvSource({"6,360", "12,720", "24,1440", "36,2160"})
    void storedCapacityDeterminesInterval(int capacity, long interval) {
        LivingSoulSlabClock clock = LivingSoulSlabClock.start(DIMENSION, 0, 20, capacity);
        assertEquals(interval, clock.deadline());
        assertEquals(interval, LivingSoulSlabClock.interval(20, capacity));
    }

    @Test void twentyOpportunitiesInSixMinutes() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.start(DIMENSION, 0, 20, 6);
        for (int step = 1; step <= 20; step++) {
            assertEquals(step * 360L, clock.deadline());
            clock = clock.next(clock.deadline());
        }
        assertEquals(7560, clock.deadline());
    }

    @Test void zeroAndInvalidInputsDoNotYieldFiniteInterval() {
        assertFalse(Double.isFinite(LivingSoulSlabClock.interval(0, 6)));
        assertFalse(Double.isFinite(LivingSoulSlabClock.interval(-1, 6)));
        assertFalse(Double.isFinite(LivingSoulSlabClock.interval(Double.NaN, 6)));
        assertFalse(Double.isFinite(LivingSoulSlabClock.interval(20, 0)));
    }

    @Test void fractionalIntervalsRetainRemainder() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.start(DIMENSION, 0, 7, 6);
        for (int step = 1; step <= 700; step++) {
            assertEquals((long) Math.floor(step * (7200.0 / 7)), clock.deadline());
            clock = clock.next(clock.deadline());
        }
    }

    @Test void hungerMonotonicallyChangesFrequency() {
        assertEquals(720, LivingSoulSlabClock.interval(10, 6));
        assertEquals(360, LivingSoulSlabClock.interval(20, 6));
        assertEquals(180, LivingSoulSlabClock.interval(40, 6));
    }

    @Test void hugeHungerIsLimitedToOneStepPerTick() {
        assertEquals(1, LivingSoulSlabClock.interval(Integer.MAX_VALUE, 6));
    }

    @Test void dimensionInputsAndClockRollbackInvalidateSchedule() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.start(DIMENSION, 100, 20, 6);
        assertTrue(clock.matches(DIMENSION, 150, 20, 6));
        assertFalse(clock.matches(ResourceLocation.parse("minecraft:the_nether"), 150, 20, 6));
        assertFalse(clock.matches(DIMENSION, 99, 20, 6));
        assertFalse(clock.matches(DIMENSION, 150, 10, 6));
        assertFalse(clock.matches(DIMENSION, 150, 20, 12));
    }

    @Test void overdueScheduleRestartsWithoutCatchup() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.start(DIMENSION, 0, 20, 6);
        assertEquals(10360, clock.next(10000).deadline());
    }

    @Test void hugeIntervalsDoNotOverflowDeadline() {
        LivingSoulSlabClock clock = LivingSoulSlabClock.start(DIMENSION, 100, Double.MIN_NORMAL, 6);
        assertEquals(Long.MAX_VALUE, clock.deadline());
        assertEquals(0, clock.remainder());
    }
}
