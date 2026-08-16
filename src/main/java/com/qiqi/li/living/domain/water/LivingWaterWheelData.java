package com.qiqi.li.living.domain.water;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.LivingItem;

public record LivingWaterWheelData(WaterWheelData wheel) implements TooltipProvider {

    public static final LivingWaterWheelData EMPTY = new LivingWaterWheelData(WaterWheelData.EMPTY);

    public static final Codec<LivingWaterWheelData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            WaterWheelData.CODEC.fieldOf("wheel").forGetter(LivingWaterWheelData::wheel)
        ).apply(instance, LivingWaterWheelData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaterWheelData> STREAM_CODEC = StreamCodec.composite(
        WaterWheelData.STREAM_CODEC, LivingWaterWheelData::wheel,
        LivingWaterWheelData::new
    );

    public LivingWaterWheelData withWheel(WaterWheelData w) { return new LivingWaterWheelData(w); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}