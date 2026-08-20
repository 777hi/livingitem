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

public record LivingRedstoneData(
    int signalStrength,
    boolean isPowered,
    byte connections
) implements TooltipProvider {

    public static final byte CONN_UP = 1 << 0;
    public static final byte CONN_DOWN = 1 << 1;
    public static final byte CONN_LEFT = 1 << 2;
    public static final byte CONN_RIGHT = 1 << 3;

    public static final LivingRedstoneData DEFAULT = new LivingRedstoneData(0, false, (byte)0);

    public static final Codec<LivingRedstoneData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("signal_strength").forGetter(LivingRedstoneData::signalStrength),
            Codec.BOOL.fieldOf("is_powered").forGetter(LivingRedstoneData::isPowered),
            Codec.BYTE.optionalFieldOf("connections", (byte)0).forGetter(LivingRedstoneData::connections)
        ).apply(instance, LivingRedstoneData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingRedstoneData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, LivingRedstoneData::signalStrength,
        ByteBufCodecs.BOOL, LivingRedstoneData::isPowered,
        ByteBufCodecs.BYTE, LivingRedstoneData::connections,
        LivingRedstoneData::new
    );

    public LivingRedstoneData withSignal(int strength) {
        return new LivingRedstoneData(strength, isPowered, connections);
    }

    public LivingRedstoneData withPowered(boolean powered) {
        return new LivingRedstoneData(signalStrength, powered, connections);
    }

    public LivingRedstoneData withConnections(byte connections) {
        return new LivingRedstoneData(signalStrength, isPowered, connections);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}