package com.qiqi.li.living.domain.hopper;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.LivingComponents;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * 活漏斗数据。
 *
 * <p>过滤链<b>不在</b>这里 —— 它是容器派生的快照数据，住在独立组件
 * {@code LIVING_HOPPER_FILTER}，每 tick 由 {@code HopperFilterBuilder} 从快照重建。
 * （原 {@code filter} 字段为旧存档兼容残留，2026-09-27 删除：本模组处于 alpha，
 * 不做旧存档兼容。判据见 `docs/README.md` 铁律 6。）
 */
public record LivingHopperData(
    DirectionTransferData direction,
    TransferData transfer,
    ResolvedSlotData slotInfo,
    boolean disabled
) implements TooltipProvider {

    public static final LivingHopperData DEFAULT = new LivingHopperData(
        DirectionTransferData.DEFAULT, TransferData.DEFAULT, ResolvedSlotData.EMPTY, false
    );

    public static final Codec<LivingHopperData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            DirectionTransferData.CODEC.fieldOf("direction").forGetter(LivingHopperData::direction),
            TransferData.CODEC.fieldOf("transfer").forGetter(LivingHopperData::transfer),
            ResolvedSlotData.CODEC.optionalFieldOf("slot_info", ResolvedSlotData.EMPTY).forGetter(LivingHopperData::slotInfo),
            Codec.BOOL.optionalFieldOf("disabled", false).forGetter(LivingHopperData::disabled)
        ).apply(instance, LivingHopperData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingHopperData> STREAM_CODEC = StreamCodec.composite(
        DirectionTransferData.STREAM_CODEC, LivingHopperData::direction,
        TransferData.STREAM_CODEC, LivingHopperData::transfer,
        ResolvedSlotData.STREAM_CODEC, LivingHopperData::slotInfo,
        StreamCodec.of(
            (buf, value) -> buf.writeBoolean(value),
            buf -> buf.readBoolean()
        ), LivingHopperData::disabled,
        LivingHopperData::new
    );

    public LivingHopperData withDirection(DirectionTransferData d) { return new LivingHopperData(d, transfer, slotInfo, disabled); }
    public LivingHopperData withTransfer(TransferData t) { return new LivingHopperData(direction, t, slotInfo, disabled); }
    public LivingHopperData withSlotInfo(ResolvedSlotData s) { return new LivingHopperData(direction, transfer, s, disabled); }
    public LivingHopperData withDisabled(boolean d) { return new LivingHopperData(direction, transfer, slotInfo, d); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getHopperData） */
    public static LivingHopperData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_HOPPER_DATA.value(), LivingHopperData.DEFAULT);
    }

    /**
     * 便捷方法：设置漏斗数据。
     */

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setHopperData） */
    public static void set(ItemStack stack, LivingHopperData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_HOPPER_DATA.value(), data, LivingHopperData.DEFAULT);
    }

    /**
     * 便捷方法：读取漏斗过滤链（tooltip 展示用；规则本体由 HopperFilterBuilder 每 tick 派生）。
     */
}
