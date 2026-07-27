package com.qiqi.li.living.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record TransformData(
    String inputItem,
    String outputItem,
    String cachedInput,
    int cachedResult,
    String cachedOutput,
    int cachedOutputCount,
    int cookingTime
) {

    public static final TransformData EMPTY = new TransformData("", "", "", 0, "", 1, 0);

    public static final Codec<TransformData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.fieldOf("input_item").forGetter(TransformData::inputItem),
            Codec.STRING.fieldOf("output_item").forGetter(TransformData::outputItem),
            Codec.STRING.fieldOf("cached_input").forGetter(TransformData::cachedInput),
            Codec.INT.fieldOf("cached_result").forGetter(TransformData::cachedResult),
            Codec.STRING.fieldOf("cached_output").forGetter(TransformData::cachedOutput),
            Codec.INT.fieldOf("cached_output_count").forGetter(TransformData::cachedOutputCount),
            Codec.INT.fieldOf("cooking_time").forGetter(TransformData::cookingTime)
        ).apply(instance, TransformData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, TransformData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TransformData decode(RegistryFriendlyByteBuf buf) {
            return new TransformData(
                buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readVarInt(), buf.readUtf(),
                buf.readVarInt(), buf.readVarInt()
            );
        }
        @Override
        public void encode(RegistryFriendlyByteBuf buf, TransformData data) {
            buf.writeUtf(data.inputItem); buf.writeUtf(data.outputItem);
            buf.writeUtf(data.cachedInput); buf.writeVarInt(data.cachedResult);
            buf.writeUtf(data.cachedOutput); buf.writeVarInt(data.cachedOutputCount);
            buf.writeVarInt(data.cookingTime);
        }
    };

    public TransformData withInputItem(String item) { return new TransformData(item, outputItem, cachedInput, cachedResult, cachedOutput, cachedOutputCount, cookingTime); }
    public TransformData withOutputItem(String item) { return new TransformData(inputItem, item, cachedInput, cachedResult, cachedOutput, cachedOutputCount, cookingTime); }
    public TransformData withCache(String input, int result, String output, int count, int time) {
        return new TransformData(inputItem, outputItem, input, result, output, count, time);
    }
    public TransformData clearCache() { return new TransformData(inputItem, outputItem, "", 0, "", 1, 0); }
    public boolean hasCache() { return !cachedInput.isEmpty() && cachedResult > 0; }
}