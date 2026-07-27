package com.qiqi.li.living.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record FilterData(
    List<String> blacklist,
    List<String> whitelist,
    List<Integer> blacklistSlots,
    List<Integer> whitelistSlots,
    List<String> blComposites,
    List<String> wlComposites,
    List<String> blTags,
    List<String> wlTags,
    List<Integer> blTagSlots,
    List<Integer> wlTagSlots
) {

    public static final FilterData EMPTY = new FilterData(
        List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of()
    );

    public static final Codec<FilterData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.STRING.listOf().fieldOf("blacklist").forGetter(FilterData::blacklist),
            Codec.STRING.listOf().fieldOf("whitelist").forGetter(FilterData::whitelist),
            Codec.INT.listOf().fieldOf("blacklist_slots").forGetter(FilterData::blacklistSlots),
            Codec.INT.listOf().fieldOf("whitelist_slots").forGetter(FilterData::whitelistSlots),
            Codec.STRING.listOf().fieldOf("bl_composites").forGetter(FilterData::blComposites),
            Codec.STRING.listOf().fieldOf("wl_composites").forGetter(FilterData::wlComposites),
            Codec.STRING.listOf().fieldOf("bl_tags").forGetter(FilterData::blTags),
            Codec.STRING.listOf().fieldOf("wl_tags").forGetter(FilterData::wlTags),
            Codec.INT.listOf().fieldOf("bl_tag_slots").forGetter(FilterData::blTagSlots),
            Codec.INT.listOf().fieldOf("wl_tag_slots").forGetter(FilterData::wlTagSlots)
        ).apply(instance, FilterData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, FilterData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FilterData decode(RegistryFriendlyByteBuf buf) {
            return new FilterData(
                readStringList(buf), readStringList(buf),
                readIntList(buf), readIntList(buf),
                readStringList(buf), readStringList(buf),
                readStringList(buf), readStringList(buf),
                readIntList(buf), readIntList(buf)
            );
        }
        @Override
        public void encode(RegistryFriendlyByteBuf buf, FilterData data) {
            writeStringList(buf, data.blacklist); writeStringList(buf, data.whitelist);
            writeIntList(buf, data.blacklistSlots); writeIntList(buf, data.whitelistSlots);
            writeStringList(buf, data.blComposites); writeStringList(buf, data.wlComposites);
            writeStringList(buf, data.blTags); writeStringList(buf, data.wlTags);
            writeIntList(buf, data.blTagSlots); writeIntList(buf, data.wlTagSlots);
        }
    };

    public FilterData {
        blacklist = Collections.unmodifiableList(new ArrayList<>(blacklist));
        whitelist = Collections.unmodifiableList(new ArrayList<>(whitelist));
        blacklistSlots = Collections.unmodifiableList(new ArrayList<>(blacklistSlots));
        whitelistSlots = Collections.unmodifiableList(new ArrayList<>(whitelistSlots));
        blComposites = Collections.unmodifiableList(new ArrayList<>(blComposites));
        wlComposites = Collections.unmodifiableList(new ArrayList<>(wlComposites));
        blTags = Collections.unmodifiableList(new ArrayList<>(blTags));
        wlTags = Collections.unmodifiableList(new ArrayList<>(wlTags));
        blTagSlots = Collections.unmodifiableList(new ArrayList<>(blTagSlots));
        wlTagSlots = Collections.unmodifiableList(new ArrayList<>(wlTagSlots));
    }

    private static List<String> readStringList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(buf.readUtf());
        return list;
    }

    private static void writeStringList(RegistryFriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<Integer> readIntList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Integer> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(buf.readVarInt());
        return list;
    }

    private static void writeIntList(RegistryFriendlyByteBuf buf, List<Integer> list) {
        buf.writeVarInt(list.size());
        for (int i : list) buf.writeVarInt(i);
    }
}