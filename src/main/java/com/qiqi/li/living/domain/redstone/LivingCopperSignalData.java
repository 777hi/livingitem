package com.qiqi.li.living.domain.redstone;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

public record LivingCopperSignalData(
    int signalStrength
) implements TooltipProvider {

    public static final LivingCopperSignalData DEFAULT = new LivingCopperSignalData(0);

    public static final Codec<LivingCopperSignalData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("signal_strength").forGetter(LivingCopperSignalData::signalStrength)
        ).apply(instance, LivingCopperSignalData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingCopperSignalData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, LivingCopperSignalData::signalStrength,
        LivingCopperSignalData::new
    );

    public LivingCopperSignalData withSignal(int signalStrength) {
        return new LivingCopperSignalData(signalStrength);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}