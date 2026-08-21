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
import com.qiqi.li.living.model.Pos2D;

public record LivingComparatorData(
    Pos2D direction,
    boolean subtractMode,
    boolean powered
) implements TooltipProvider {

    public static final LivingComparatorData DEFAULT = new LivingComparatorData(Pos2D.UP, false, false);

    public static final Codec<LivingComparatorData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("direction").forGetter(LivingComparatorData::direction),
            Codec.BOOL.fieldOf("subtract_mode").forGetter(LivingComparatorData::subtractMode),
            Codec.BOOL.fieldOf("powered").forGetter(LivingComparatorData::powered)
        ).apply(instance, LivingComparatorData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingComparatorData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingComparatorData::direction,
        ByteBufCodecs.BOOL, LivingComparatorData::subtractMode,
        ByteBufCodecs.BOOL, LivingComparatorData::powered,
        LivingComparatorData::new
    );

    public LivingComparatorData withDirection(Pos2D dir) {
        return new LivingComparatorData(dir, subtractMode, powered);
    }

    public LivingComparatorData withSubtractMode(boolean mode) {
        return new LivingComparatorData(direction, mode, powered);
    }

    public LivingComparatorData withPowered(boolean p) {
        return new LivingComparatorData(direction, subtractMode, p);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}