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
    /** 遗留兼容字段（b064865 起逻辑不读取、不再写入）：过滤链迁至独立组件 LIVING_HOPPER_FILTER，由 tick 从快照回写。保留仅为旧存档反序列化兼容 */
    FilterData filter,
    ResolvedSlotData slotInfo,
    boolean disabled
) implements TooltipProvider {

    public static final LivingHopperData DEFAULT = new LivingHopperData(
        DirectionTransferData.DEFAULT, TransferData.DEFAULT, FilterData.EMPTY, ResolvedSlotData.EMPTY, false
    );

    public static final Codec<LivingHopperData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            DirectionTransferData.CODEC.fieldOf("direction").forGetter(LivingHopperData::direction),
            TransferData.CODEC.fieldOf("transfer").forGetter(LivingHopperData::transfer),
            FilterData.CODEC.optionalFieldOf("filter", FilterData.EMPTY).forGetter(LivingHopperData::filter),
            ResolvedSlotData.CODEC.optionalFieldOf("slot_info", ResolvedSlotData.EMPTY).forGetter(LivingHopperData::slotInfo),
            Codec.BOOL.optionalFieldOf("disabled", false).forGetter(LivingHopperData::disabled)
        ).apply(instance, LivingHopperData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingHopperData> STREAM_CODEC = StreamCodec.composite(
        DirectionTransferData.STREAM_CODEC, LivingHopperData::direction,
        TransferData.STREAM_CODEC, LivingHopperData::transfer,
        FilterData.STREAM_CODEC, LivingHopperData::filter,
        ResolvedSlotData.STREAM_CODEC, LivingHopperData::slotInfo,
        StreamCodec.of(
            (buf, value) -> buf.writeBoolean(value),
            buf -> buf.readBoolean()
        ), LivingHopperData::disabled,
        LivingHopperData::new
    );

    public LivingHopperData withDirection(DirectionTransferData d) { return new LivingHopperData(d, transfer, filter, slotInfo, disabled); }
    public LivingHopperData withTransfer(TransferData t) { return new LivingHopperData(direction, t, filter, slotInfo, disabled); }
    public LivingHopperData withFilter(FilterData f) { return new LivingHopperData(direction, transfer, f, slotInfo, disabled); }
    public LivingHopperData withSlotInfo(ResolvedSlotData s) { return new LivingHopperData(direction, transfer, filter, s, disabled); }
    public LivingHopperData withDisabled(boolean d) { return new LivingHopperData(direction, transfer, filter, slotInfo, d); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}
}