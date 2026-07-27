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

public record LivingHopperData(
    DirectionTransferData direction,
    TransferData transfer,
    FilterData filter
) implements TooltipProvider {

    public static final LivingHopperData DEFAULT = new LivingHopperData(
        DirectionTransferData.DEFAULT, TransferData.DEFAULT, FilterData.EMPTY
    );

    public static final Codec<LivingHopperData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            DirectionTransferData.CODEC.fieldOf("direction").forGetter(LivingHopperData::direction),
            TransferData.CODEC.fieldOf("transfer").forGetter(LivingHopperData::transfer),
            FilterData.CODEC.fieldOf("filter").forGetter(LivingHopperData::filter)
        ).apply(instance, LivingHopperData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingHopperData> STREAM_CODEC = StreamCodec.composite(
        DirectionTransferData.STREAM_CODEC, LivingHopperData::direction,
        TransferData.STREAM_CODEC, LivingHopperData::transfer,
        FilterData.STREAM_CODEC, LivingHopperData::filter,
        LivingHopperData::new
    );

    public LivingHopperData withDirection(DirectionTransferData d) { return new LivingHopperData(d, transfer, filter); }
    public LivingHopperData withTransfer(TransferData t) { return new LivingHopperData(direction, t, filter); }
    public LivingHopperData withFilter(FilterData f) { return new LivingHopperData(direction, transfer, f); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}