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
import com.qiqi.li.living.data.FuelData;
import com.qiqi.li.living.data.ProgressData;
import com.qiqi.li.living.data.DirectionSlotsData;
import com.qiqi.li.living.data.TransformData;

public record LivingFurnaceData(
    ProgressData progress,
    FuelData fuel,
    DirectionSlotsData direction,
    TransformData transform
) implements TooltipProvider {

    public static final LivingFurnaceData DEFAULT = new LivingFurnaceData(
        ProgressData.DEFAULT, FuelData.DEFAULT, DirectionSlotsData.DEFAULT_FURNACE, TransformData.EMPTY
    );

    public static final Codec<LivingFurnaceData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            ProgressData.CODEC.fieldOf("progress").forGetter(LivingFurnaceData::progress),
            FuelData.CODEC.fieldOf("fuel").forGetter(LivingFurnaceData::fuel),
            DirectionSlotsData.CODEC.fieldOf("direction").forGetter(LivingFurnaceData::direction),
            TransformData.CODEC.fieldOf("transform").forGetter(LivingFurnaceData::transform)
        ).apply(instance, LivingFurnaceData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingFurnaceData> STREAM_CODEC = StreamCodec.composite(
        ProgressData.STREAM_CODEC, LivingFurnaceData::progress,
        FuelData.STREAM_CODEC, LivingFurnaceData::fuel,
        DirectionSlotsData.STREAM_CODEC, LivingFurnaceData::direction,
        TransformData.STREAM_CODEC, LivingFurnaceData::transform,
        LivingFurnaceData::new
    );

    public LivingFurnaceData withProgress(ProgressData p) { return new LivingFurnaceData(p, fuel, direction, transform); }
    public LivingFurnaceData withFuel(FuelData f) { return new LivingFurnaceData(progress, f, direction, transform); }
    public LivingFurnaceData withDirection(DirectionSlotsData d) { return new LivingFurnaceData(progress, fuel, d, transform); }
    public LivingFurnaceData withTransform(TransformData t) { return new LivingFurnaceData(progress, fuel, direction, t); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}