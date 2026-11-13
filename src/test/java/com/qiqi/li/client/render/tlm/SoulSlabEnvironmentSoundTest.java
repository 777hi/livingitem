package com.qiqi.li.client.render.tlm;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoulSlabEnvironmentSoundTest {
    private final SoundEvent morning = SoundEvent.createVariableRangeEvent(id("morning"));
    private final SoundEvent night = SoundEvent.createVariableRangeEvent(id("night"));
    private final SoundEvent rain = SoundEvent.createVariableRangeEvent(id("rain"));
    private final SoundEvent snow = SoundEvent.createVariableRangeEvent(id("snow"));
    private final SoundEvent cold = SoundEvent.createVariableRangeEvent(id("cold"));
    private final SoundEvent hot = SoundEvent.createVariableRangeEvent(id("hot"));
    private final SoundEvent idle = SoundEvent.createVariableRangeEvent(id("idle"));
    private final SoulSlabEnvironmentSound.Options sounds =
            new SoulSlabEnvironmentSound.Options(morning, night, rain, snow, cold, hot, idle);

    @Test
    void morningAndNightRepeatAcrossMinecraftDays() {
        RandomSource random = always(0);
        assertSame(morning, select(24001, false, Biome.Precipitation.NONE, false, false, random));
        assertSame(night, select(36001, false, Biome.Precipitation.NONE, false, false, random));
    }

    @Test
    void strictTimeBoundariesFallThrough() {
        RandomSource random = always(0);
        assertSame(idle, select(0, false, Biome.Precipitation.NONE, false, false, random));
        assertSame(idle, select(3000, false, Biome.Precipitation.NONE, false, false, random));
        assertSame(idle, select(12000, false, Biome.Precipitation.NONE, false, false, random));
        assertSame(idle, select(15000, false, Biome.Precipitation.NONE, false, false, random));
    }

    @Test
    void weatherThenTemperatureUseTlmPriority() {
        RandomSource random = always(0);
        assertSame(rain, select(6000, true, Biome.Precipitation.RAIN, false, false, random));
        assertSame(snow, select(6000, true, Biome.Precipitation.SNOW, true, false, random));
        assertSame(cold, select(6000, false, Biome.Precipitation.NONE, true, false, random));
        assertSame(hot, select(6000, false, Biome.Precipitation.NONE, false, true, random));
    }

    @Test
    void failedProbabilityFallsBack() {
        assertSame(idle, select(1000, true, Biome.Precipitation.RAIN, true, true, always(1)));
    }

    private SoundEvent select(long time, boolean raining, Biome.Precipitation precipitation,
                              boolean coldEnoughToSnow, boolean hot, RandomSource random) {
        return SoulSlabEnvironmentSound.select(
                new SoulSlabEnvironmentSound.Context(time, raining, precipitation, coldEnoughToSnow, hot),
                random, sounds);
    }

    private static RandomSource always(float value) {
        RandomSource random = mock(RandomSource.class);
        when(random.nextFloat()).thenReturn(value);
        return random;
    }

    private static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("living_item", path);
    }
}
