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
}