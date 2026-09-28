package com.qiqi.li.living.domain.ender;import net.minecraft.world.item.ItemStack;


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

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getEnderChestData） */
    public static LivingEnderChestData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_ENDER_CHEST_DATA.value(), LivingEnderChestData.EMPTY);
    }

    /**
     * 便捷方法：设置末影箱数据。
     */

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setEnderChestData） */
    public static void set(ItemStack stack, LivingEnderChestData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_ENDER_CHEST_DATA.value(), data, LivingEnderChestData.EMPTY);
    }
}
