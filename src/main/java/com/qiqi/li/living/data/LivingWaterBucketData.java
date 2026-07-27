package com.qiqi.li.living.data;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

public record LivingWaterBucketData(WaterData water) implements TooltipProvider {

    public static final LivingWaterBucketData EMPTY = new LivingWaterBucketData(WaterData.EMPTY);

    public static final Codec<LivingWaterBucketData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            WaterData.CODEC.fieldOf("water").forGetter(LivingWaterBucketData::water)
        ).apply(instance, LivingWaterBucketData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaterBucketData> STREAM_CODEC = StreamCodec.composite(
        WaterData.STREAM_CODEC, LivingWaterBucketData::water,
        LivingWaterBucketData::new
    );

    public LivingWaterBucketData withWater(WaterData w) { return new LivingWaterBucketData(w); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}