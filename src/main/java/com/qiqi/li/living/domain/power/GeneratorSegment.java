package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 涂蜡发电机遥测的运行时数据片段（档 2）。
 *
 * <p>把原先写在 {@code LivingItemRuntimeData} 里的发电机那一组字段、
 * 以及 {@code LivingItemSyncPacket} 里的 {@code encodeGenerator/decodeGenerator}
 * 收归本领域自己持有 —— 框架层（{@code living/runtime}）不再认识本类型。</p>
 *
 * <p>⚠️ {@link #id()} 进网络包，**发布后不可改名**。</p>
 */
public final class GeneratorSegment implements RuntimeSegmentType<LivingWaxedGeneratorData> {

    public static final GeneratorSegment INSTANCE = new GeneratorSegment();

    private GeneratorSegment() {}

    @Override
    public String id() {
        return "generator";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, LivingWaxedGeneratorData> codec() {
        return LivingWaxedGeneratorData.STREAM_CODEC;
    }
}
