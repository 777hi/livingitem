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

public record LivingRepeaterData(
    Pos2D direction,
    int delay,
    boolean powered,
    int delayTimer,
    boolean locked
) implements TooltipProvider {

    public static final LivingRepeaterData DEFAULT = new LivingRepeaterData(Pos2D.UP, 1, false, 0, false);

    public static final Codec<LivingRepeaterData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("direction").forGetter(LivingRepeaterData::direction),
            Codec.INT.fieldOf("delay").forGetter(LivingRepeaterData::delay),
            Codec.BOOL.fieldOf("powered").forGetter(LivingRepeaterData::powered),
            Codec.INT.fieldOf("delay_timer").forGetter(LivingRepeaterData::delayTimer),
            Codec.BOOL.optionalFieldOf("locked", false).forGetter(LivingRepeaterData::locked)
        ).apply(instance, LivingRepeaterData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingRepeaterData> STREAM_CODEC = StreamCodec.composite(
        Pos2D.STREAM_CODEC, LivingRepeaterData::direction,
        ByteBufCodecs.INT, LivingRepeaterData::delay,
        ByteBufCodecs.BOOL, LivingRepeaterData::powered,
        ByteBufCodecs.INT, LivingRepeaterData::delayTimer,
        ByteBufCodecs.BOOL, LivingRepeaterData::locked,
        LivingRepeaterData::new
    );

    public LivingRepeaterData withDirection(Pos2D dir) {
        return new LivingRepeaterData(dir, delay, powered, delayTimer, locked);
    }

    public LivingRepeaterData withDelay(int newDelay) {
        return new LivingRepeaterData(direction, newDelay, powered, delayTimer, locked);
    }

    public LivingRepeaterData withPowered(boolean p) {
        return new LivingRepeaterData(direction, delay, p, delayTimer, locked);
    }

    public LivingRepeaterData withDelayTimer(int timer) {
        return new LivingRepeaterData(direction, delay, powered, timer, locked);
    }

    public LivingRepeaterData withLocked(boolean l) {
        return new LivingRepeaterData(direction, delay, powered, delayTimer, l);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}