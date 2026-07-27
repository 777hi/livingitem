package com.qiqi.li.living.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record TransferData(int cooldown) {

    public static final TransferData DEFAULT = new TransferData(0);

    public static final Codec<TransferData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("cooldown").forGetter(TransferData::cooldown)
        ).apply(instance, TransferData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, TransferData> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.INT, TransferData::cooldown,
        TransferData::new
    );

    public TransferData withCooldown(int cd) { return new TransferData(cd); }
    public TransferData tick() { return new TransferData(Math.max(0, cooldown - 1)); }
    public boolean isOnCooldown() { return cooldown > 0; }
}