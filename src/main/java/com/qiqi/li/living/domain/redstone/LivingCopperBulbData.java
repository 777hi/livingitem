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
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record LivingCopperBulbData(
    int recordedSignal,
    boolean prevInput
) implements TooltipProvider {

    public static final LivingCopperBulbData DEFAULT = new LivingCopperBulbData(0, false);

    public static final Codec<LivingCopperBulbData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("recorded_signal").forGetter(LivingCopperBulbData::recordedSignal),
            Codec.BOOL.fieldOf("prev_input").forGetter(LivingCopperBulbData::prevInput)
        ).apply(instance, LivingCopperBulbData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingCopperBulbData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, LivingCopperBulbData::recordedSignal,
        ByteBufCodecs.BOOL, LivingCopperBulbData::prevInput,
        LivingCopperBulbData::new
    );

    public LivingCopperBulbData withRecordedSignal(int recordedSignal) {
        return new LivingCopperBulbData(recordedSignal, prevInput);
    }

    public LivingCopperBulbData withPrevInput(boolean prevInput) {
        return new LivingCopperBulbData(recordedSignal, prevInput);
    }

    public boolean isLit() {
        return recordedSignal > 0;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /** 读取：缺失返回默认值。（A1 迁移：原 LivingItemManager.getCopperBulbData） */
    public static LivingCopperBulbData of(ItemStack stack) {
        return LivingItemManager.getData(stack, LivingComponents.LIVING_COPPER_BULB_DATA.value(), LivingCopperBulbData.DEFAULT);
    }

    /** 写入：等于默认值时移除组件。（A1 迁移：原 LivingItemManager.setCopperBulbData） */
    public static void set(ItemStack stack, LivingCopperBulbData data) {
        LivingItemManager.setData(stack, LivingComponents.LIVING_COPPER_BULB_DATA.value(), data, LivingCopperBulbData.DEFAULT);
    }
}
