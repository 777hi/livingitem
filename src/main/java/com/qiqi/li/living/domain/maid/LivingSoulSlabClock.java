package com.qiqi.li.living.domain.maid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** 每枚魂符的均匀迈步排期；分数余量避免非整tick间隔长期漂移。 */
public record LivingSoulSlabClock(ResourceLocation dimension, long scheduledAt, long deadline,
                                  double remainder, double hunger, int capacity) {
    public static final Codec<LivingSoulSlabClock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(LivingSoulSlabClock::dimension),
            Codec.LONG.fieldOf("scheduled_at").forGetter(LivingSoulSlabClock::scheduledAt),
            Codec.LONG.fieldOf("deadline").forGetter(LivingSoulSlabClock::deadline),
            Codec.DOUBLE.fieldOf("remainder").forGetter(LivingSoulSlabClock::remainder),
            Codec.DOUBLE.fieldOf("hunger").forGetter(LivingSoulSlabClock::hunger),
            Codec.INT.fieldOf("capacity").forGetter(LivingSoulSlabClock::capacity)
    ).apply(instance, LivingSoulSlabClock::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingSoulSlabClock> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, LivingSoulSlabClock::dimension,
            ByteBufCodecs.VAR_LONG, LivingSoulSlabClock::scheduledAt,
            ByteBufCodecs.VAR_LONG, LivingSoulSlabClock::deadline,
            ByteBufCodecs.DOUBLE, LivingSoulSlabClock::remainder,
            ByteBufCodecs.DOUBLE, LivingSoulSlabClock::hunger,
            ByteBufCodecs.VAR_INT, LivingSoulSlabClock::capacity,
            LivingSoulSlabClock::new);

    public static double interval(double hunger, int capacity) {
        if (!Double.isFinite(hunger) || hunger <= 0 || capacity <= 0) return Double.POSITIVE_INFINITY;
        return Math.max(1.0, 1200.0 * capacity / hunger);
    }

    public static LivingSoulSlabClock start(ResourceLocation dimension, long now, double hunger, int capacity) {
        return schedule(dimension, now, now, 0, hunger, capacity);
    }

    public boolean matches(ResourceLocation currentDimension, long now, double currentHunger, int currentCapacity) {
        return dimension.equals(currentDimension) && hunger == currentHunger && capacity == currentCapacity
                && now >= scheduledAt && deadline > scheduledAt
                && Double.isFinite(remainder) && remainder >= 0 && remainder < 1;
    }

    public LivingSoulSlabClock next(long now) {
        // 只保留正常到点的余量；过期不追补历史机会。
        double carry = now == deadline ? remainder : 0;
        return schedule(dimension, now, now, carry, hunger, capacity);
    }

    private static LivingSoulSlabClock schedule(ResourceLocation dimension, long scheduledAt, long base,
                                               double carry, double hunger, int capacity) {
        double exact = interval(hunger, capacity) + carry;
        long ticks = !Double.isFinite(exact) || exact >= Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(1L, (long) Math.floor(exact));
        long deadline = base > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : base + ticks;
        double remainder = ticks == Long.MAX_VALUE ? 0 : exact - ticks;
        return new LivingSoulSlabClock(dimension, scheduledAt, deadline, remainder, hunger, capacity);
    }
}
