package com.qiqi.li.living;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 容器区块缓存 —— 维护"包含容器方块实体的区块"列表，避免每 tick 全量扫描所有区块。
 *
 * 为什么需要这个缓存：
 *   活物品 tick 需要遍历世界中所有容器来处理活物品逻辑。
 *   如果每 tick 都扫描所有已加载区块的所有方块实体，性能开销极大。
 *   实际上大部分区块不包含任何容器，所以只需缓存"有容器的区块"即可。
 *
 * 缓存维护策略（事件驱动）：
 *   - 区块加载 → 扫描该区块，若有容器则加入缓存
 *   - 区块卸载 → 从缓存中移除
 *   - 方块放置/破坏 → 重新扫描该区块，更新缓存
 *
 * 使用方式：
 *   在 {@link com.qiqi.li.LivingItem#onServerTick} 中，
 *   通过 {@link #getCachedChunks} 获取当前维度的容器区块列表，
 *   只遍历这些区块中的方块实体。
 */
public class ContainerChunkCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ContainerChunkCache INSTANCE = new ContainerChunkCache();

    /** 按维度存储包含容器的区块坐标集合 */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> chunkCache = new Object2ObjectOpenHashMap<>();

    private ContainerChunkCache() {}

    public static ContainerChunkCache getInstance() {
        return INSTANCE;
    }

    /**
     * 区块加载事件 —— 扫描新加载的区块，若有容器则加入缓存。
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPos pos = event.getChunk().getPos();
            if (level.hasChunk(pos.x, pos.z)) {
                scanChunkForContainers(level, level.getChunk(pos.x, pos.z));
            }
        }
    }

    /**
     * 区块卸载事件 —— 从缓存中移除该区块。
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ResourceKey<Level> dim = level.dimension();
            ChunkPos pos = event.getChunk().getPos();

            Set<ChunkPos> chunkSet = chunkCache.get(dim);
            if (chunkSet != null) {
                chunkSet.remove(pos);
            }
        }
    }

    /**
     * 方块放置事件 —— 如果放置的是容器方块，重新扫描该区块。
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockEntity be = level.getBlockEntity(event.getPos());
            if (be instanceof Container) {
                ChunkPos pos = new ChunkPos(event.getPos());
                if (level.hasChunk(pos.x, pos.z)) {
                    scanChunkForContainers(level, level.getChunk(pos.x, pos.z));
                }
            }
        }
    }

    /**
     * 方块破坏事件 —— 如果破坏的是容器方块，重新扫描该区块。
     * 即使破坏后该区块仍有其他容器，重新扫描也能正确更新缓存。
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockEntity be = level.getBlockEntity(event.getPos());
            if (be instanceof Container) {
                ChunkPos pos = new ChunkPos(event.getPos());
                if (level.hasChunk(pos.x, pos.z)) {
                    scanChunkForContainers(level, level.getChunk(pos.x, pos.z));
                }
            }
        }
    }

    /**
     * 扫描指定区块，检查是否包含容器方块实体，更新缓存。
     *
     * @param level 服务端世界
     * @param chunk 要扫描的区块
     */
    private void scanChunkForContainers(ServerLevel level, LevelChunk chunk) {
        ResourceKey<Level> dim = level.dimension();
        ChunkPos pos = chunk.getPos();

        boolean hasContainer = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof Container) {
                hasContainer = true;
                break;
            }
        }

        Set<ChunkPos> chunkSet = chunkCache.computeIfAbsent(dim, k -> new ObjectOpenHashSet<>());
        if (hasContainer) {
            chunkSet.add(pos);
        } else {
            chunkSet.remove(pos);
        }
    }

    /**
     * 获取指定维度中包含容器的区块坐标集合。
     *
     * @param dim 维度 Key
     * @return 不可修改的区块坐标集合；如果该维度没有缓存则返回空集合
     */
    public Set<ChunkPos> getCachedChunks(ResourceKey<Level> dim) {
        return chunkCache.getOrDefault(dim, Collections.emptySet());
    }

    /** 清空所有缓存（用于服务端关闭或维度卸载等场景） */
    public void clear() {
        chunkCache.clear();
    }
}