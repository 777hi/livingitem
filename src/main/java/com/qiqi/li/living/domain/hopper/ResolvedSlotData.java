package com.qiqi.li.living.domain.hopper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活漏斗的解析槽位数据。
 *
 * <p>记录发起槽位（host）、解析出的源 / 目标槽位与容器尺寸 / 宽度，并提供「是否越界（跨容器）」等判定，
 * 供悬浮提示与 {@code CrossContainerTransfer} 使用。</p>
 */
public record ResolvedSlotData(
    int hostSlot,
    int sourceSlot,
    int targetSlot,
    int containerSize,
    int containerWidth
) {

    public static final ResolvedSlotData EMPTY = new ResolvedSlotData(-1, -1, -1, 0, 0);

    public static final Codec<ResolvedSlotData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("host_slot").forGetter(ResolvedSlotData::hostSlot),
            Codec.INT.fieldOf("source_slot").forGetter(ResolvedSlotData::sourceSlot),
            Codec.INT.fieldOf("target_slot").forGetter(ResolvedSlotData::targetSlot),
            Codec.INT.fieldOf("container_size").forGetter(ResolvedSlotData::containerSize),
            Codec.INT.fieldOf("container_width").forGetter(ResolvedSlotData::containerWidth)
        ).apply(instance, ResolvedSlotData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ResolvedSlotData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, ResolvedSlotData::hostSlot,
        ByteBufCodecs.INT, ResolvedSlotData::sourceSlot,
        ByteBufCodecs.INT, ResolvedSlotData::targetSlot,
        ByteBufCodecs.INT, ResolvedSlotData::containerSize,
        ByteBufCodecs.INT, ResolvedSlotData::containerWidth,
        ResolvedSlotData::new
    );

    public boolean sourceOutOfBounds() { return sourceSlot < 0 || sourceSlot >= containerSize; }
    public boolean targetOutOfBounds() { return targetSlot < 0 || targetSlot >= containerSize; }
    public boolean isCrossContainer() { return sourceOutOfBounds() || targetOutOfBounds(); }
    public boolean isValid() { return containerSize > 0; }
}