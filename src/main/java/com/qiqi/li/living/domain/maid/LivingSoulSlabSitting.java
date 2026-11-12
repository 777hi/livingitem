package com.qiqi.li.living.domain.maid;

import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** 活魂符的坐下标记：true = 空光标右键后坐下，随机走位暂停。 */
public record LivingSoulSlabSitting(boolean value) {
    public static final Codec<LivingSoulSlabSitting> CODEC = Codec.BOOL.xmap(
            LivingSoulSlabSitting::new, LivingSoulSlabSitting::value);
    public static final StreamCodec<RegistryFriendlyByteBuf, LivingSoulSlabSitting> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, LivingSoulSlabSitting::value, LivingSoulSlabSitting::new);
}
