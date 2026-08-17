package com.qiqi.li.living.domain.hopper;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.living.transfer.FilterData;

public record LivingHopperData(
    DirectionTransferData direction,
    TransferData transfer,
    FilterData filter,
    ResolvedSlotData slotInfo
) implements TooltipProvider {

    public static final LivingHopperData DEFAULT = new LivingHopperData(
        DirectionTransferData.DEFAULT, TransferData.DEFAULT, FilterData.EMPTY, ResolvedSlotData.EMPTY
    );

    public static final Codec<LivingHopperData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            DirectionTransferData.CODEC.fieldOf("direction").forGetter(LivingHopperData::direction),
            TransferData.CODEC.fieldOf("transfer").forGetter(LivingHopperData::transfer),
            FilterData.CODEC.optionalFieldOf("filter", FilterData.EMPTY).forGetter(LivingHopperData::filter),
            ResolvedSlotData.CODEC.optionalFieldOf("slot_info", ResolvedSlotData.EMPTY).forGetter(LivingHopperData::slotInfo)
        ).apply(instance, LivingHopperData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingHopperData> STREAM_CODEC = StreamCodec.composite(
        DirectionTransferData.STREAM_CODEC, LivingHopperData::direction,
        TransferData.STREAM_CODEC, LivingHopperData::transfer,
        FilterData.STREAM_CODEC, LivingHopperData::filter,
        ResolvedSlotData.STREAM_CODEC, LivingHopperData::slotInfo,
        LivingHopperData::new
    );

    public LivingHopperData withDirection(DirectionTransferData d) { return new LivingHopperData(d, transfer, filter, slotInfo); }
    public LivingHopperData withTransfer(TransferData t) { return new LivingHopperData(direction, t, filter, slotInfo); }
    public LivingHopperData withFilter(FilterData f) { return new LivingHopperData(direction, transfer, f, slotInfo); }
    public LivingHopperData withSlotInfo(ResolvedSlotData s) { return new LivingHopperData(direction, transfer, filter, s); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}