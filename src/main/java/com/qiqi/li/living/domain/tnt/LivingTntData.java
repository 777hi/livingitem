package com.qiqi.li.living.domain.tnt;import net.minecraft.world.item.ItemStack;


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

/**
 * 活 TNT 的数据组件。
 *
 * <p>封装 {@link ExplosionData} 爆炸状态，经 {@link #of} / {@link #set} 读写，并实现编解码与悬浮提示接口。</p>
 */
public record LivingTntData(ExplosionData explosion) implements TooltipProvider {

    public static final LivingTntData DEFAULT = new LivingTntData(ExplosionData.DEFAULT);

    public static final Codec<LivingTntData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            ExplosionData.CODEC.fieldOf("explosion").forGetter(LivingTntData::explosion)
        ).apply(instance, LivingTntData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingTntData> STREAM_CODEC = StreamCodec.composite(
        ExplosionData.STREAM_CODEC, LivingTntData::explosion,
        LivingTntData::new
    );

    public LivingTntData withExplosion(ExplosionData e) { return new LivingTntData(e); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getTntData） */
    public static LivingTntData of(ItemStack stack) {
        return LivingItemManager.getData(stack, TntComponents.LIVING_TNT_DATA.value(), LivingTntData.DEFAULT);
    }

    /**
     * 便捷方法：设置TNT数据。
     */

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setTntData） */
    public static void set(ItemStack stack, LivingTntData data) {
        LivingItemManager.setData(stack, TntComponents.LIVING_TNT_DATA.value(), data, LivingTntData.DEFAULT);
    }

    /**
     * 便捷方法：获取水桶数据。
     */
}
