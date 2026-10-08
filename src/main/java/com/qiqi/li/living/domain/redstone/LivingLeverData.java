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
 * 活拉杆的数据组件。
 *
 * <p>封装拉杆的开启/关闭状态（{@code powered}）。
 * 经 {@link #of} / {@link #set} 读写，并实现编解码（{@code CODEC} / {@code STREAM_CODEC}）与悬浮提示接口。</p>
 */
public record LivingLeverData(
    boolean powered
) implements TooltipProvider {

    public static final LivingLeverData DEFAULT = new LivingLeverData(false);

    public static final Codec<LivingLeverData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.fieldOf("powered").forGetter(LivingLeverData::powered)
        ).apply(instance, LivingLeverData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingLeverData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, LivingLeverData::powered,
        LivingLeverData::new
    );

    public LivingLeverData withPowered(boolean powered) {
        return new LivingLeverData(powered);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getLeverData） */
    public static LivingLeverData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_LEVER_DATA.value(), LivingLeverData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setLeverData） */
    public static void set(ItemStack stack, LivingLeverData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_LEVER_DATA.value(), data, LivingLeverData.DEFAULT);
    }
}
