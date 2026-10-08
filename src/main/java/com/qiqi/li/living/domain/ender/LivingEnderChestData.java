package com.qiqi.li.living.domain.ender;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 活末影箱的数据组件。
 *
 * <p>封装活末影箱的频道信息（{@link EnderChannelData}），经 {@link #of} / {@link #set} 读写，
 * 并实现编解码（{@code CODEC} / {@code STREAM_CODEC}）与悬浮提示接口。</p>
 */
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

    /**
     * 组件注册（2026-10-08：从 {@code LivingComponents} 拆回本领域）。
     *
     * <p>定义在 {@link EnderComponents}（而非本类）—— 那里在 {@code RegisterEvent} 之前完成登记。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingEnderChestData>> COMPONENT =
        EnderComponents.LIVING_ENDER_CHEST_DATA;
    public LivingEnderChestData withChannel(EnderChannelData c) { return new LivingEnderChestData(c); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getEnderChestData） */
    public static LivingEnderChestData of(ItemStack stack) {
        return LivingItemManager.getData(stack, COMPONENT.value(), LivingEnderChestData.EMPTY);
    }

    /**
     * 便捷方法：设置末影箱数据。
     */

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setEnderChestData） */
    public static void set(ItemStack stack, LivingEnderChestData data) {
        LivingItemManager.setData(stack, COMPONENT.value(), data, LivingEnderChestData.EMPTY);
    }
}
