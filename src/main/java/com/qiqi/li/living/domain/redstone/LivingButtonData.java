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
 * 活按钮的数据组件。
 *
 * <p>封装按钮的按下状态、脉冲计时与材质（木/石，{@link #getPulseDuration} 据此返回不同脉冲时长），
 * 经 {@link #of} / {@link #set} 读写，并实现编解码（{@code CODEC} / {@code STREAM_CODEC}）与悬浮提示接口。</p>
 */
public record LivingButtonData(
    boolean pressed,
    int pulseTimer,
    boolean isWood
) implements TooltipProvider {

    public static final LivingButtonData DEFAULT = new LivingButtonData(false, 0, false);

    public static final Codec<LivingButtonData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.fieldOf("pressed").forGetter(LivingButtonData::pressed),
            Codec.INT.fieldOf("pulse_timer").forGetter(LivingButtonData::pulseTimer),
            Codec.BOOL.fieldOf("is_wood").forGetter(LivingButtonData::isWood)
        ).apply(instance, LivingButtonData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingButtonData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, LivingButtonData::pressed,
        ByteBufCodecs.INT, LivingButtonData::pulseTimer,
        ByteBufCodecs.BOOL, LivingButtonData::isWood,
        LivingButtonData::new
    );

    public LivingButtonData withPressed(boolean pressed) {
        return new LivingButtonData(pressed, pulseTimer, isWood);
    }

    public LivingButtonData withPulseTimer(int timer) {
        return new LivingButtonData(pressed, timer, isWood);
    }

    public LivingButtonData withWood(boolean wood) {
        return new LivingButtonData(pressed, pulseTimer, wood);
    }

    public int getPulseDuration() {
        return isWood ? 30 : 20;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getButtonData） */
    public static LivingButtonData of(ItemStack stack) {
        return LivingItemManager.getData(stack, RedstoneComponents.LIVING_BUTTON_DATA.value(), LivingButtonData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setButtonData） */
    public static void set(ItemStack stack, LivingButtonData data) {
        LivingItemManager.setData(stack, RedstoneComponents.LIVING_BUTTON_DATA.value(), data, LivingButtonData.DEFAULT);
    }
}
