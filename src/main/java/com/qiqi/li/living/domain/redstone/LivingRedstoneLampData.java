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
 * 活红石灯的数据组件。
 *
 * <p>封装点亮状态（{@code lit}）。
 * 经 {@link #of} / {@link #set} 读写，并实现编解码（{@code CODEC} / {@code STREAM_CODEC}）与悬浮提示接口。</p>
 */
public record LivingRedstoneLampData(
    boolean lit
) implements TooltipProvider {

    public static final LivingRedstoneLampData DEFAULT = new LivingRedstoneLampData(false);

    public static final Codec<LivingRedstoneLampData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.fieldOf("lit").forGetter(LivingRedstoneLampData::lit)
        ).apply(instance, LivingRedstoneLampData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingRedstoneLampData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, LivingRedstoneLampData::lit,
        LivingRedstoneLampData::new
    );

    public LivingRedstoneLampData withLit(boolean lit) {
        return new LivingRedstoneLampData(lit);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getLampData） */
    public static LivingRedstoneLampData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_REDSTONE_LAMP_DATA.value(), LivingRedstoneLampData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setLampData） */
    public static void set(ItemStack stack, LivingRedstoneLampData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_REDSTONE_LAMP_DATA.value(), data, LivingRedstoneLampData.DEFAULT);
    }
}
