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
}