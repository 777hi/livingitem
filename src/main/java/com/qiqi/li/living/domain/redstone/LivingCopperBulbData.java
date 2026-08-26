package com.qiqi.li.living.domain.redstone;

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
}