package com.qiqi.li.living.domain.hopper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活漏斗的传输节奏数据（不可变）。
 *
 * <p>记录当前冷却计数（{@code cooldown}），提供递减与「是否在冷却中」判定——所有方法返回新实例。</p>
 */
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