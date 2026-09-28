package com.qiqi.li.living.domain.redstone;import net.minecraft.world.item.ItemStack;


import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.LivingComponents;
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

public record LivingGrateData(
    int sumSignal
) implements TooltipProvider {

    public static final LivingGrateData DEFAULT = new LivingGrateData(0);

    public static final Codec<LivingGrateData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("sum_signal").forGetter(LivingGrateData::sumSignal)
        ).apply(instance, LivingGrateData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingGrateData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, LivingGrateData::sumSignal,
        LivingGrateData::new
    );

    public LivingGrateData withSumSignal(int sumSignal) {
        return new LivingGrateData(sumSignal);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getGrateData） */
    public static LivingGrateData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_GRATE_DATA.value(), LivingGrateData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setGrateData） */
    public static void set(ItemStack stack, LivingGrateData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_GRATE_DATA.value(), data, LivingGrateData.DEFAULT);
    }
}
