package com.qiqi.li.living.domain.ender;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

public record LivingEnderChestData(EnderChannelData channel) implements TooltipProvider {

    public static final LivingEnderChestData EMPTY = new LivingEnderChestData(EnderChannelData.EMPTY);

    public static final Codec<LivingEnderChestData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            EnderChannelData.CODEC.fieldOf("channel").forGetter(LivingEnderChestData::channel)
        ).apply(instance, LivingEnderChestData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingEnderChestData> STREAM_CODEC = StreamCodec.composite(
        EnderChannelData.STREAM_CODEC, LivingEnderChestData::channel,
        LivingEnderChestData::new
    );

    public LivingEnderChestData withChannel(EnderChannelData c) { return new LivingEnderChestData(c); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}