package com.qiqi.li.living.domain.furnace;

import com.qiqi.li.living.runtime.RuntimeSegmentType;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活熔炉烧制进度的运行时数据片段（档 2）。
 *
 * <p>把原先 {@code LivingItemRuntimeData.FurnaceRuntime} 与
 * {@code LivingItemSyncPacket} 里那一组字段收归本领域。</p>
 *
 * <p>⚠️ {@link #id()} 进网络包，**发布后不可改名**。</p>
 */
public final class FurnaceSegment implements RuntimeSegmentType<FurnaceSegment.FurnaceRuntime> {

    public static final FurnaceSegment INSTANCE = new FurnaceSegment();

    private FurnaceSegment() {}

    @Override
    public String id() {
        return "furnace";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, FurnaceSegment.FurnaceRuntime> codec() {
        return FurnaceRuntime.STREAM_CODEC;
    }

    /**
     * 活熔炉运行时：烧制进度、燃料、配方缓存。
     *
     * <p>（原 {@code LivingItemRuntimeData.FurnaceRuntime}，档 2 迁入本领域。）</p>
     */
    public record FurnaceRuntime(int progress, int total, int burnTime, TransformData transform) {

        public static final FurnaceRuntime EMPTY = new FurnaceRuntime(0, 0, 0, null);

        /**
         * 编解码 —— 与改造前 {@code encodeFurnace/decodeFurnace} **字节格式一致**：
         * {@code varint progress} + {@code varint total} + {@code varint burnTime}
         * + {@code bool hasTransform} + [7 字段 transform]。
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceRuntime> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public FurnaceRuntime decode(RegistryFriendlyByteBuf buf) {
                    int progress = buf.readVarInt();
                    int total = buf.readVarInt();
                    int burnTime = buf.readVarInt();
                    TransformData transform = buf.readBoolean()
                        ? TransformData.STREAM_CODEC.decode(buf) : null;
                    return new FurnaceRuntime(progress, total, burnTime, transform);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, FurnaceRuntime f) {
                    buf.writeVarInt(f.progress());
                    buf.writeVarInt(f.total());
                    buf.writeVarInt(f.burnTime());
                    if (f.transform() != null) {
                        buf.writeBoolean(true);
                        TransformData.STREAM_CODEC.encode(buf, f.transform());
                    } else {
                        buf.writeBoolean(false);
                    }
                }
            };
    }
}
