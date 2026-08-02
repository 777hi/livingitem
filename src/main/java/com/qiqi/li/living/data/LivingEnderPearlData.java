package com.qiqi.li.living.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record LivingEnderPearlData(int cooldown) {

    public static final LivingEnderPearlData DEFAULT = new LivingEnderPearlData(0);

    public static final Codec<LivingEnderPearlData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("cooldown").forGetter(LivingEnderPearlData::cooldown)
        ).apply(instance, LivingEnderPearlData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingEnderPearlData> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT, LivingEnderPearlData::cooldown,
        LivingEnderPearlData::new
    );

    public LivingEnderPearlData withCooldown(int cooldown) {
        return new LivingEnderPearlData(cooldown);
    }
}