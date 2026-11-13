package com.qiqi.li.client.render.tlm;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

/** TLM环境语音顺序；日内时间先规范到单个Minecraft日。 */
final class SoulSlabEnvironmentSound {
    static final float PROBABILITY = 0.5f;

    record Context(long dayTime, boolean raining, Biome.Precipitation precipitation,
                   boolean coldEnoughToSnow, boolean hot) {}

    record Options(SoundEvent morning, SoundEvent night, SoundEvent rain, SoundEvent snow,
                   SoundEvent cold, SoundEvent hot, SoundEvent fallback) {}

    private SoulSlabEnvironmentSound() {}

    static SoundEvent select(Context context, RandomSource random, Options sounds) {
        long dayTime = Math.floorMod(context.dayTime(), 24000L);
        if (random.nextFloat() < PROBABILITY && 0 < dayTime && dayTime < 3000) return sounds.morning();
        if (random.nextFloat() < PROBABILITY && 12000 < dayTime && dayTime < 15000) return sounds.night();
        if (random.nextFloat() < PROBABILITY && context.raining()
                && context.precipitation() == Biome.Precipitation.RAIN && !context.hot()) return sounds.rain();
        if (random.nextFloat() < PROBABILITY && context.raining()
                && context.precipitation() == Biome.Precipitation.SNOW) return sounds.snow();
        if (random.nextFloat() < PROBABILITY && context.coldEnoughToSnow()) return sounds.cold();
        if (random.nextFloat() < PROBABILITY && context.hot()) return sounds.hot();
        return sounds.fallback();
    }
}
