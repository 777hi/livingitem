package com.qiqi.li.living.domain.hopper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import com.qiqi.li.living.model.Pos2D;

public record DirectionTransferData(Pos2D sourceOffset, Pos2D targetOffset) {

    public static final DirectionTransferData DEFAULT = new DirectionTransferData(Pos2D.UP, Pos2D.DOWN);

    public static final Codec<DirectionTransferData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("source").forGetter(DirectionTransferData::sourceOffset),
            Pos2D.CODEC.fieldOf("target").forGetter(DirectionTransferData::targetOffset)
        ).apply(instance, DirectionTransferData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, DirectionTransferData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, DirectionTransferData::sourceOffset,
        Pos2D.STREAM_CODEC, DirectionTransferData::targetOffset,
        DirectionTransferData::new
    );

    public DirectionTransferData withSource(Pos2D src) { return new DirectionTransferData(src, targetOffset); }
    public DirectionTransferData withTarget(Pos2D tgt) { return new DirectionTransferData(sourceOffset, tgt); }
}