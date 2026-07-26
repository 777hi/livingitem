package com.qiqi.li.living.core.accessor;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EnderChannelClientCache {

    private static final Map<Integer, ChannelSnapshot> cache = new ConcurrentHashMap<>();

    public record EntryDisplay(
        String itemType,
        @Nullable String dimKey,
        @Nullable BlockPos sourcePos,
        int sourceSlot
    ) {}

    public record ChannelSnapshot(
        int channelSize,
        int totalRoutes,
        List<EntryDisplay> entries
    ) {}

    public static void update(int channel, int channelSize, int totalRoutes,
                              List<EntryDisplay> entries) {
        cache.put(channel, new ChannelSnapshot(channelSize, totalRoutes, entries));
    }

    public static ChannelSnapshot getSnapshot(int channel) {
        return cache.getOrDefault(channel, new ChannelSnapshot(0, 0, List.of()));
    }

    public static void clear() {
        cache.clear();
    }

    public static void removeChannel(int channel) {
        cache.remove(channel);
    }
}