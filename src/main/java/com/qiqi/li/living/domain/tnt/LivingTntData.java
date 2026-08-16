package com.qiqi.li.living.domain.tnt;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

public record LivingTntData(ExplosionData explosion) implements TooltipProvider {

    public static final LivingTntData DEFAULT = new LivingTntData(ExplosionData.DEFAULT);

    public static final Codec<LivingTntData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            ExplosionData.CODEC.fieldOf("explosion").forGetter(LivingTntData::explosion)
        ).apply(instance, LivingTntData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingTntData> STREAM_CODEC = StreamCodec.composite(
        ExplosionData.STREAM_CODEC, LivingTntData::explosion,
        LivingTntData::new
    );

    public LivingTntData withExplosion(ExplosionData e) { return new LivingTntData(e); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}