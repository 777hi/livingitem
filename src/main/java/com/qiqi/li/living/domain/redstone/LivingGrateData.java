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

public record LivingGrateData(
    boolean lastInput,
    boolean output
) implements TooltipProvider {

    public static final LivingGrateData DEFAULT = new LivingGrateData(false, false);

    public static final Codec<LivingGrateData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.fieldOf("last_input").forGetter(LivingGrateData::lastInput),
            Codec.BOOL.fieldOf("output").forGetter(LivingGrateData::output)
        ).apply(instance, LivingGrateData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingGrateData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, LivingGrateData::lastInput,
        ByteBufCodecs.BOOL, LivingGrateData::output,
        LivingGrateData::new
    );

    public LivingGrateData withLastInput(boolean lastInput) {
        return new LivingGrateData(lastInput, output);
    }

    public LivingGrateData withOutput(boolean output) {
        return new LivingGrateData(lastInput, output);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}