package com.qiqi.li.living.domain.furnace;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record FuelData(int burnTime) {

    public static final FuelData DEFAULT = new FuelData(0);

    public static final Codec<FuelData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("burn_time").forGetter(FuelData::burnTime)
        ).apply(instance, FuelData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, FuelData> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.INT, FuelData::burnTime,
        FuelData::new
    );

    public FuelData withBurnTime(int bt) { return new FuelData(bt); }
    public boolean isBurning() { return burnTime > 0; }
    public FuelData tick(int count) { return new FuelData(Math.max(0, burnTime - count)); }
}