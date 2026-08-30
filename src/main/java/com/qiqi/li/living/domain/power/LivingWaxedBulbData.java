package com.qiqi.li.living.domain.power;

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

/**
 * 涂蜡铜灯 —— 电池（§3.6 v17.5：纯容器，无充放限率）。
 *
 * <p>电量按「每盏」存（chargeMilliFe = 每盏电量，1/1000 FE 定点）：
 * 拆分时组件被复制、每盏各带 q，总电量 count × q 天然守恒——
 * 按「每堆总量」存则拆分即刷电。容量 = 每盏 C（{@link PowerMath#BULB_UNIT_CAPACITY_FE}）
 * × count（线性，平方容量在拆分时坍缩毁电，弃用）。
 * 电量不同的两堆不会自动合并（原版组件堆叠语义），同电量合并天然守恒。</p>
 */
public record LivingWaxedBulbData(
    long chargeMilliFe
) implements TooltipProvider {

    public static final LivingWaxedBulbData DEFAULT = new LivingWaxedBulbData(0);

    public static final Codec<LivingWaxedBulbData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.LONG.fieldOf("charge_milli_fe").forGetter(LivingWaxedBulbData::chargeMilliFe)
        ).apply(instance, LivingWaxedBulbData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaxedBulbData> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, LivingWaxedBulbData::chargeMilliFe,
            LivingWaxedBulbData::new
        );

    public LivingWaxedBulbData withChargeMilliFe(long chargeMilliFe) {
        return new LivingWaxedBulbData(chargeMilliFe);
    }

    /** 该堆总电量（mFE）= 每盏 × 数量 */
    public long totalChargeMilliFe(int count) {
        return chargeMilliFe * count;
    }

    /** 该堆总容量（mFE）= 每盏 C × 数量 */
    public static long totalCapacityMilliFe(int count) {
        return PowerMath.BULB_UNIT_CAPACITY_MFE * count;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}
