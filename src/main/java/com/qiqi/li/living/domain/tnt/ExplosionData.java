package com.qiqi.li.living.domain.tnt;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活 TNT 的爆炸状态数据（不可变）。
 *
 * <p>记录是否已点燃（{@code ignited}）与引信倒计时（{@code fuseTimer}），并提供点燃、每 tick 递减、
 * 是否爆炸、重置等状态转移方法——所有方法返回新实例，原实例不变。</p>
 */
public record ExplosionData(boolean ignited, int fuseTimer) {

    public static final ExplosionData DEFAULT = new ExplosionData(false, 0);

    public static final Codec<ExplosionData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.fieldOf("ignited").forGetter(ExplosionData::ignited),
            Codec.INT.fieldOf("fuse_timer").forGetter(ExplosionData::fuseTimer)
        ).apply(instance, ExplosionData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ExplosionData> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.BOOL, ExplosionData::ignited,
        net.minecraft.network.codec.ByteBufCodecs.INT, ExplosionData::fuseTimer,
        ExplosionData::new
    );

    public ExplosionData ignite(int fuseDuration) { return new ExplosionData(true, fuseDuration); }
    public ExplosionData ignite() { return ignite(80); }
    public ExplosionData tick() { return new ExplosionData(ignited, fuseTimer - 1); }
    public boolean isExploded() { return ignited && fuseTimer <= 0; }
    public ExplosionData reset() { return DEFAULT; }
}