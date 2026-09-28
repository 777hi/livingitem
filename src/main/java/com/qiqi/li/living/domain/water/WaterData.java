package com.qiqi.li.living.domain.water;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record WaterData(
    long lastTick,
    int hostSlot,
    int hostCol,
    int hostRow,
    int width,
    String containerKey,
    String flow
) {

    public static final WaterData EMPTY = new WaterData(0, 0, 0, 0, 1, "", "");

    public static final Codec<WaterData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.LONG.fieldOf("last_tick").forGetter(WaterData::lastTick),
            Codec.INT.fieldOf("host_slot").forGetter(WaterData::hostSlot),
            Codec.INT.fieldOf("host_col").forGetter(WaterData::hostCol),
            Codec.INT.fieldOf("host_row").forGetter(WaterData::hostRow),
            Codec.INT.fieldOf("width").forGetter(WaterData::width),
            Codec.STRING.fieldOf("container_key").forGetter(WaterData::containerKey),
            Codec.STRING.fieldOf("flow").forGetter(WaterData::flow)
        ).apply(instance, WaterData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, WaterData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WaterData decode(RegistryFriendlyByteBuf buf) {
            return new WaterData(
                buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(), buf.readUtf()
            );
        }
        @Override
        public void encode(RegistryFriendlyByteBuf buf, WaterData data) {
            buf.writeLong(data.lastTick);
            buf.writeVarInt(data.hostSlot); buf.writeVarInt(data.hostCol);
            buf.writeVarInt(data.hostRow); buf.writeVarInt(data.width);
            buf.writeUtf(data.containerKey); buf.writeUtf(data.flow);
        }
    };

    public WaterData withLastTick(long tick) { return new WaterData(tick, hostSlot, hostCol, hostRow, width, containerKey, flow); }
    public WaterData withFlow(String f) { return new WaterData(lastTick, hostSlot, hostCol, hostRow, width, containerKey, f); }
    public WaterData withPosition(int slot, int col, int row, int w, String key) {
        return new WaterData(lastTick, slot, col, row, w, key, flow);
    }
}