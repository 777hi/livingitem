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
import com.qiqi.li.living.model.Pos2D;

/**
 * 活比较器的数据组件。
 *
 * <p>封装比较器朝向（{@link Pos2D}）、减法模式与对外输出强度（比较 / 减法结果，每 tick 由传播层
 * 重算并写回，0 = 无输出），经 {@link #of} / {@link #set} 读写，并实现编解码
 * （{@code CODEC} / {@code STREAM_CODEC}）与悬浮提示接口。</p>
 */
public record LivingComparatorData(
    Pos2D direction,
    boolean subtractMode,
    int output
) implements TooltipProvider {

    public static final LivingComparatorData DEFAULT = new LivingComparatorData(Pos2D.UP, false, 0);

    public static final Codec<LivingComparatorData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("direction").forGetter(LivingComparatorData::direction),
            Codec.BOOL.fieldOf("subtract_mode").forGetter(LivingComparatorData::subtractMode),
            Codec.INT.optionalFieldOf("output", 0).forGetter(LivingComparatorData::output)
        ).apply(instance, LivingComparatorData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingComparatorData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingComparatorData::direction,
        ByteBufCodecs.BOOL, LivingComparatorData::subtractMode,
        ByteBufCodecs.INT, LivingComparatorData::output,
        LivingComparatorData::new
    );

    public LivingComparatorData withDirection(Pos2D dir) {
        return new LivingComparatorData(dir, subtractMode, output);
    }

    public LivingComparatorData withSubtractMode(boolean mode) {
        return new LivingComparatorData(direction, mode, output);
    }

    public LivingComparatorData withOutput(int value) {
        return new LivingComparatorData(direction, subtractMode, value);
    }

    /** 是否有输出：等价于 {@link #output()} > 0（只关心有/无的调用方用它）。 */
    public boolean powered() {
        return output > 0;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getComparatorData） */
    public static LivingComparatorData of(ItemStack stack) {
        return LivingItemManager.getData(stack, RedstoneComponents.LIVING_COMPARATOR_DATA.value(), LivingComparatorData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setComparatorData） */
    public static void set(ItemStack stack, LivingComparatorData data) {
        LivingItemManager.setData(stack, RedstoneComponents.LIVING_COMPARATOR_DATA.value(), data, LivingComparatorData.DEFAULT);
    }
}
