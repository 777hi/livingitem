package com.qiqi.li.living.domain.maid;

import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** 每枚活魂符的自适应移动活跃度；I 表示平均多少秒发起一次尝试。 */
public record LivingSoulSlabClock(int intervalSeconds) {
    public static final int INITIAL_INTERVAL_SECONDS = 7;
    public static final int MIN_INTERVAL_SECONDS = 1;
    public static final int MAX_INTERVAL_SECONDS = 49;

    public static final Codec<LivingSoulSlabClock> CODEC = Codec.INT.xmap(
            LivingSoulSlabClock::new, LivingSoulSlabClock::intervalSeconds);
    public static final StreamCodec<RegistryFriendlyByteBuf, LivingSoulSlabClock> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, LivingSoulSlabClock::intervalSeconds,
                    LivingSoulSlabClock::new);

    public static LivingSoulSlabClock initial() {
        return new LivingSoulSlabClock(INITIAL_INTERVAL_SECONDS);
    }

    public boolean valid() {
        return intervalSeconds >= MIN_INTERVAL_SECONDS && intervalSeconds <= MAX_INTERVAL_SECONDS;
    }

    public LivingSoulSlabClock afterSuccess() {
        return new LivingSoulSlabClock(Math.max(MIN_INTERVAL_SECONDS, intervalSeconds - 1));
    }

    public LivingSoulSlabClock afterFailure() {
        return new LivingSoulSlabClock(Math.min(MAX_INTERVAL_SECONDS, intervalSeconds + 1));
    }
}
