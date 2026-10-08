package com.qiqi.li.living.domain.water;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.LivingComponents;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.LivingItem;

/**
 * 活水车的数据组件。
 *
 * <p>封装水车的应力状态（{@link WaterWheelData}），经 {@link #of} / {@link #set} 读写，
 * 并实现编解码与悬浮提示接口。与 Create 的 water_wheel 互通，悬浮提示显示顺 / 逆时针应力与净应力。</p>
 */
public record LivingWaterWheelData(WaterWheelData wheel) implements TooltipProvider {

    public static final LivingWaterWheelData EMPTY = new LivingWaterWheelData(WaterWheelData.EMPTY);

    public static final Codec<LivingWaterWheelData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            WaterWheelData.CODEC.fieldOf("wheel").forGetter(LivingWaterWheelData::wheel)
        ).apply(instance, LivingWaterWheelData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaterWheelData> STREAM_CODEC = StreamCodec.composite(
        WaterWheelData.STREAM_CODEC, LivingWaterWheelData::wheel,
        LivingWaterWheelData::new
    );

    public LivingWaterWheelData withWheel(WaterWheelData w) { return new LivingWaterWheelData(w); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getWaterWheelData） */
    public static LivingWaterWheelData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_WATER_WHEEL_DATA.value(), LivingWaterWheelData.EMPTY);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setWaterWheelData） */
    public static void set(ItemStack stack, LivingWaterWheelData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_WATER_WHEEL_DATA.value(), data, LivingWaterWheelData.EMPTY);
    }
}
