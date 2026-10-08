package com.qiqi.li.living.domain.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端地图元数据缓存。
 *
 * <p>以 mapId 为键缓存地图中心坐标与所在维度（LRU，上限 64 条），供客户端渲染准心目标点使用。
 * 元数据来自服务端在玩家 tick 时下发的数据包；客户端断线时调用 {@link #clear} 避免残留旧 mapId。</p>
 */
public final class LivingMapClientCache {

    private static final int MAX_CACHE_SIZE = 64;

    private static final Map<Integer, MapMetadata> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, MapMetadata> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

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

    /**
     * 清空缓存，在客户端断开连接时调用，避免切换存档/服务器后残留旧 mapId 的中心坐标。
     */
    public static void clear() {
        cache.clear();
    }
}