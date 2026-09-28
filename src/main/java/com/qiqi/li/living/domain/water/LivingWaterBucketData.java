package com.qiqi.li.living.domain.water;import net.minecraft.world.item.ItemStack;


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

public record LivingWaterBucketData(WaterData water) implements TooltipProvider {

    public static final LivingWaterBucketData EMPTY = new LivingWaterBucketData(WaterData.EMPTY);

    public static final Codec<LivingWaterBucketData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            WaterData.CODEC.fieldOf("water").forGetter(LivingWaterBucketData::water)
        ).apply(instance, LivingWaterBucketData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaterBucketData> STREAM_CODEC = StreamCodec.composite(
        WaterData.STREAM_CODEC, LivingWaterBucketData::water,
        LivingWaterBucketData::new
    );

    public LivingWaterBucketData withWater(WaterData w) { return new LivingWaterBucketData(w); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getWaterBucketData） */
    public static LivingWaterBucketData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_WATER_BUCKET_DATA.value(), LivingWaterBucketData.EMPTY);
    }

    /**
     * 便捷方法：设置水桶数据。
     */

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setWaterBucketData） */
    public static void set(ItemStack stack, LivingWaterBucketData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_WATER_BUCKET_DATA.value(), data, LivingWaterBucketData.EMPTY);
    }

    /**
     * 便捷方法：获取末影箱数据。
     */
}
