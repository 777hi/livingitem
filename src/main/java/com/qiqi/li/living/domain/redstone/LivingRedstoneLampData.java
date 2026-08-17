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

public record LivingRedstoneLampData(
    boolean lit
) implements TooltipProvider {

    public static final LivingRedstoneLampData DEFAULT = new LivingRedstoneLampData(false);

    public static final Codec<LivingRedstoneLampData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.BOOL.fieldOf("lit").forGetter(LivingRedstoneLampData::lit)
        ).apply(instance, LivingRedstoneLampData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingRedstoneLampData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, LivingRedstoneLampData::lit,
        LivingRedstoneLampData::new
    );

    public LivingRedstoneLampData withLit(boolean lit) {
        return new LivingRedstoneLampData(lit);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}