package com.qiqi.li.living.domain.redstone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;

/** 活侦测器只持久化输出方向；观测基线与脉冲相位属于容器槽位运行态。 */
public record LivingObserverData(Pos2D direction) {

    public static final LivingObserverData DEFAULT = new LivingObserverData(Pos2D.UP);

    private static final Codec<Pos2D> DIRECTION_CODEC = Pos2D.CODEC.validate(direction ->
        isCardinal(direction)
            ? DataResult.success(direction)
            : DataResult.error(() -> "Observer direction must be cardinal"));

    public static final Codec<LivingObserverData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            DIRECTION_CODEC.fieldOf("direction").forGetter(LivingObserverData::direction)
        ).apply(instance, LivingObserverData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingObserverData> STREAM_CODEC =
        StreamCodec.composite(Pos2D.STREAM_CODEC, LivingObserverData::direction, LivingObserverData::new);

    public LivingObserverData {
        if (!isCardinal(direction)) {
            throw new IllegalArgumentException("Observer direction must be cardinal");
        }
    }

    public LivingObserverData withDirection(Pos2D dir) {
        return new LivingObserverData(dir);
    }

    public static LivingObserverData of(ItemStack stack) {
        return LivingItemManager.getData(
            stack, RedstoneComponents.LIVING_OBSERVER_DATA.value(), DEFAULT);
    }

    public static void set(ItemStack stack, LivingObserverData data) {
        LivingItemManager.setData(
            stack, RedstoneComponents.LIVING_OBSERVER_DATA.value(), data, DEFAULT);
    }

    private static boolean isCardinal(Pos2D direction) {
        return Pos2D.UP.equals(direction) || Pos2D.DOWN.equals(direction)
            || Pos2D.LEFT.equals(direction) || Pos2D.RIGHT.equals(direction);
    }
}
