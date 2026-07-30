package com.qiqi.li.living.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record WaterWheelData(
    int cwStress,
    int ccwStress,
    int netStress
) {

    public static final WaterWheelData EMPTY = new WaterWheelData(0, 0, 0);

    public static final Codec<WaterWheelData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("cw_stress").forGetter(WaterWheelData::cwStress),
            Codec.INT.fieldOf("ccw_stress").forGetter(WaterWheelData::ccwStress),
            Codec.INT.fieldOf("net_stress").forGetter(WaterWheelData::netStress)
        ).apply(instance, WaterWheelData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, WaterWheelData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WaterWheelData decode(RegistryFriendlyByteBuf buf) {
            return new WaterWheelData(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WaterWheelData data) {
            buf.writeVarInt(data.cwStress);
            buf.writeVarInt(data.ccwStress);
            buf.writeVarInt(data.netStress);
        }
    };

    public WaterWheelData withStress(int cw, int ccw, int net) {
        return new WaterWheelData(cw, ccw, net);
    }
}