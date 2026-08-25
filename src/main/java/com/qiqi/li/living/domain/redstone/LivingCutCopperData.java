package com.qiqi.li.living.domain.redstone;

import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import com.qiqi.li.living.model.Pos2D;

public record LivingCutCopperData(
    Pos2D direction
) implements TooltipProvider {

    public static final LivingCutCopperData DEFAULT = new LivingCutCopperData(Pos2D.UP);

    public static final Codec<LivingCutCopperData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("direction").forGetter(LivingCutCopperData::direction)
        ).apply(instance, LivingCutCopperData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingCutCopperData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingCutCopperData::direction,
        LivingCutCopperData::new
    );

    public LivingCutCopperData withDirection(Pos2D direction) {
        return new LivingCutCopperData(direction);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}