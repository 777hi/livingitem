package com.qiqi.li.living.domain.piston;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.piston.PistonComponents;
import com.qiqi.li.living.model.Pos2D;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** 物品只保存四向配置；伸出状态属于槽位运行时。 */
public record LivingPistonData(Pos2D direction) {

    public static final LivingPistonData DEFAULT = new LivingPistonData(Pos2D.UP);

    private static final Codec<Pos2D> DIRECTION_CODEC = Pos2D.CODEC.validate(direction ->
        PistonChainResolver.edgeDirection(direction) >= 0
            ? DataResult.success(direction)
            : DataResult.error(() -> "Piston direction must be cardinal"));

    public static final Codec<LivingPistonData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            DIRECTION_CODEC.fieldOf("direction").forGetter(LivingPistonData::direction)
        ).apply(instance, LivingPistonData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingPistonData> STREAM_CODEC =
        StreamCodec.composite(Pos2D.STREAM_CODEC, LivingPistonData::direction, LivingPistonData::new);

    public LivingPistonData {
        if (PistonChainResolver.edgeDirection(direction) < 0) {
            throw new IllegalArgumentException("Piston direction must be cardinal");
        }
    }

    public LivingPistonData withDirection(Pos2D value) {
        return new LivingPistonData(value);
    }

    public static LivingPistonData of(ItemStack stack) {
        return LivingItemManager.getData(stack, PistonComponents.LIVING_PISTON_DATA.value(), DEFAULT);
    }

    public static void set(ItemStack stack, LivingPistonData data) {
        LivingItemManager.setData(stack, PistonComponents.LIVING_PISTON_DATA.value(), data, DEFAULT);
    }
}
