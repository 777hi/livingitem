package com.qiqi.li.living.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import com.qiqi.li.living.model.Pos2D;

public record DirectionSlotsData(Map<String, Pos2D> directions, int activeSlotIndex) {

    public static final DirectionSlotsData EMPTY = new DirectionSlotsData(Map.of(), 0);

    private static final LinkedHashMap<String, Pos2D> FURNACE_ORDER = new LinkedHashMap<>() {{
        put("input", Pos2D.LEFT);
        put("output", Pos2D.RIGHT);
        put("fuel", Pos2D.DOWN);
    }};

    public static final DirectionSlotsData DEFAULT_FURNACE = new DirectionSlotsData(FURNACE_ORDER, 0);

    public static final Codec<DirectionSlotsData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.unboundedMap(Codec.STRING, Pos2D.CODEC).fieldOf("directions").forGetter(DirectionSlotsData::directions),
            Codec.INT.fieldOf("active_slot_index").forGetter(DirectionSlotsData::activeSlotIndex)
        ).apply(instance, DirectionSlotsData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, DirectionSlotsData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DirectionSlotsData decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            Map<String, Pos2D> map = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                String key = buf.readUtf();
                map.put(key, Pos2D.STREAM_CODEC.decode(buf));
            }
            int activeIndex = buf.readVarInt();
            return new DirectionSlotsData(map, activeIndex);
        }
        @Override
        public void encode(RegistryFriendlyByteBuf buf, DirectionSlotsData data) {
            buf.writeVarInt(data.directions().size());
            for (var entry : data.directions().entrySet()) {
                buf.writeUtf(entry.getKey());
                Pos2D.STREAM_CODEC.encode(buf, entry.getValue());
            }
            buf.writeVarInt(data.activeSlotIndex());
        }
    };

    public DirectionSlotsData {
        directions = Collections.unmodifiableMap(new LinkedHashMap<>(directions));
    }

    public DirectionSlotsData withDirection(String slotName, Pos2D direction) {
        Map<String, Pos2D> newMap = new LinkedHashMap<>(directions);
        newMap.put(slotName, direction);
        return new DirectionSlotsData(newMap, activeSlotIndex);
    }

    public DirectionSlotsData withActiveSlotIndex(int index) {
        return new DirectionSlotsData(directions, index);
    }

    public Pos2D getDirection(String slotName) {
        return directions.getOrDefault(slotName, Pos2D.NONE);
    }

    public String[] getSlotNames() {
        return directions.keySet().toArray(new String[0]);
    }
}