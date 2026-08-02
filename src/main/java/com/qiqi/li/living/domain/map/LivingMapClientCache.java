package com.qiqi.li.living.domain.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LivingMapClientCache {

    private static final Map<Integer, MapMetadata> cache = new ConcurrentHashMap<>();

    public record MapMetadata(int centerX, int centerZ, ResourceKey<Level> dimension) {}

    public static void update(int mapId, int centerX, int centerZ, String dimensionKey) {
        ResourceKey<Level> dimKey = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse(dimensionKey)
        );
        cache.put(mapId, new MapMetadata(centerX, centerZ, dimKey));
    }

    @Nullable
    public static MapMetadata get(int mapId) {
        return cache.get(mapId);
    }

    public static void remove(int mapId) {
        cache.remove(mapId);
    }

    public static void clear() {
        cache.clear();
    }
}