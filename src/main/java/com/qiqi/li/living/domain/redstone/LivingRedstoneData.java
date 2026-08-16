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

public record LivingRedstoneData(
    int signalStrength,
    boolean isPowered
) implements TooltipProvider {

    public static final LivingRedstoneData DEFAULT = new LivingRedstoneData(0, false);

    public static final Codec<LivingRedstoneData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("signal_strength").forGetter(LivingRedstoneData::signalStrength),
            Codec.BOOL.fieldOf("is_powered").forGetter(LivingRedstoneData::isPowered)
        ).apply(instance, LivingRedstoneData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingRedstoneData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, LivingRedstoneData::signalStrength,
        ByteBufCodecs.BOOL, LivingRedstoneData::isPowered,
        LivingRedstoneData::new
    );

    public LivingRedstoneData withSignal(int strength) {
        return new LivingRedstoneData(strength, isPowered);
    }

    public LivingRedstoneData withPowered(boolean powered) {
        return new LivingRedstoneData(signalStrength, powered);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}