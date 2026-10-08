package com.qiqi.li.living.domain.redstone;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.LivingComponents;
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
 * 活铜信号源的数据组件。
 *
 * <p>封装当前向外输出的信号强度（{@code signalStrength}）。
 * 经 {@link #of} / {@link #set} 读写，并实现编解码（{@code CODEC} / {@code STREAM_CODEC}）与悬浮提示接口。</p>
 */
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

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getCopperSignal） */
    public static LivingCopperSignalData of(ItemStack stack) {
        return LivingItemManager.getData(stack, RedstoneComponents.LIVING_COPPER_SIGNAL.value(), LivingCopperSignalData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setCopperSignal） */
    public static void set(ItemStack stack, LivingCopperSignalData data) {
        LivingItemManager.setData(stack, RedstoneComponents.LIVING_COPPER_SIGNAL.value(), data, LivingCopperSignalData.DEFAULT);
    }
}
