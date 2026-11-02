package com.qiqi.li.living.domain.piston;

import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** 活塞机械态的同步片段；不包含物品数量或持久动作历史。 */
public final class PistonSegment implements RuntimeSegmentType<PistonRuntime> {

    public static final PistonSegment INSTANCE = new PistonSegment();

    public static final StreamCodec<RegistryFriendlyByteBuf, PistonRuntime> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, PistonRuntime::extended,
            Pos2D.STREAM_CODEC, PistonRuntime::direction,
            PistonRuntime::new
        );

    private PistonSegment() {}

    @Override
    public String id() {
        return "piston";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, PistonRuntime> codec() {
        return STREAM_CODEC;
    }
}
