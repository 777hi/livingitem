package com.qiqi.li.living.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record ProgressData(int progress, int total) {

    public static final ProgressData DEFAULT = new ProgressData(0, 200);

    public static final Codec<ProgressData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("progress").forGetter(ProgressData::progress),
            Codec.INT.fieldOf("total").forGetter(ProgressData::total)
        ).apply(instance, ProgressData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ProgressData> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.INT, ProgressData::progress,
        net.minecraft.network.codec.ByteBufCodecs.INT, ProgressData::total,
        ProgressData::new
    );

    public ProgressData withProgress(int p) { return new ProgressData(p, total); }
    public ProgressData withTotal(int t) { return new ProgressData(progress, t); }
    public ProgressData reset() { return new ProgressData(0, total); }
    public boolean isComplete() { return progress >= total && total > 0; }
    public float getProgressRatio() { return total > 0 ? (float) progress / total : 0f; }

    public ProgressData advanceBy(int amount) {
        return new ProgressData(Math.min(total, progress + amount), total);
    }

    public ProgressData recedeBy(int amount) {
        return new ProgressData(Math.max(0, progress - amount), total);
    }

    public ProgressData recede() {
        return recedeBy(1);
    }
}