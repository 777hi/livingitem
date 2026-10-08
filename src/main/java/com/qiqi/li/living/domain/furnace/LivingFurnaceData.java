package com.qiqi.li.living.domain.furnace;import net.minecraft.world.item.ItemStack;


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
 * 活熔炉的数据组件。
 *
 * <p>聚合进度 / 燃料 / 方向 / 配方缓存四类子状态，经 {@link #of} / {@link #set} 读写，
 * 并实现编解码与悬浮提示接口。注意：进度、燃烧时间等<b>运行时瞬态数据走运行时缓存</b>，不进本组件，
 * 本组件只保存方向与配方缓存等持久状态。</p>
 */
public record LivingFurnaceData(
    ProgressData progress,
    FuelData fuel,
    DirectionSlotsData direction,
    TransformData transform
) implements TooltipProvider {

    public static final LivingFurnaceData DEFAULT = new LivingFurnaceData(
        ProgressData.DEFAULT, FuelData.DEFAULT, DirectionSlotsData.DEFAULT_FURNACE, TransformData.EMPTY
    );

    public static final Codec<LivingFurnaceData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            ProgressData.CODEC.fieldOf("progress").forGetter(LivingFurnaceData::progress),
            FuelData.CODEC.fieldOf("fuel").forGetter(LivingFurnaceData::fuel),
            DirectionSlotsData.CODEC.fieldOf("direction").forGetter(LivingFurnaceData::direction),
            TransformData.CODEC.fieldOf("transform").forGetter(LivingFurnaceData::transform)
        ).apply(instance, LivingFurnaceData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingFurnaceData> STREAM_CODEC = StreamCodec.composite(
        ProgressData.STREAM_CODEC, LivingFurnaceData::progress,
        FuelData.STREAM_CODEC, LivingFurnaceData::fuel,
        DirectionSlotsData.STREAM_CODEC, LivingFurnaceData::direction,
        TransformData.STREAM_CODEC, LivingFurnaceData::transform,
        LivingFurnaceData::new
    );

    public LivingFurnaceData withProgress(ProgressData p) { return new LivingFurnaceData(p, fuel, direction, transform); }
    public LivingFurnaceData withFuel(FuelData f) { return new LivingFurnaceData(progress, f, direction, transform); }
    public LivingFurnaceData withDirection(DirectionSlotsData d) { return new LivingFurnaceData(progress, fuel, d, transform); }
    public LivingFurnaceData withTransform(TransformData t) { return new LivingFurnaceData(progress, fuel, direction, t); }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {}

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getFurnaceData） */
    public static LivingFurnaceData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_FURNACE_DATA.value(), LivingFurnaceData.DEFAULT);
    }

    /**
     * 便捷方法：设置熔炉数据。
     */

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setFurnaceData） */
    public static void set(ItemStack stack, LivingFurnaceData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_FURNACE_DATA.value(), data, LivingFurnaceData.DEFAULT);
    }

    /**
     * 便捷方法：读取熔炉燃烧标志（供图标谓词使用）。
     */
}
