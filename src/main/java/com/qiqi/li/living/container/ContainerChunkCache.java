package com.qiqi.li.living.container;

import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.logging.ModLog;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

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
    private static final ContainerChunkCache INSTANCE = new ContainerChunkCache();

    /** 按维度存储包含容器的区块坐标集合 */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> chunkCache = new Object2ObjectOpenHashMap<>();

    /** 每个维度上次清理的时间戳（gameTime），用于定期清理已卸载的区块 */
    private final Map<ResourceKey<Level>, Long> lastCleanupTick = new Object2ObjectOpenHashMap<>();

    /**
     * 待重扫区块队列（按维度）。
     *
     * <p>方块放置/破坏事件触发时，目标位置的 BlockEntity 可能尚未完成初始化
     * （不少模组容器的 ItemHandler 依赖 BE 内部字段，在 {@code onLoad()} 或读 NBT 后才可用），
     * 此刻查询 capability 会返回 null，导致重扫扫不到刚放下的容器。
     * 因此把重扫推迟到下一 tick，由 {@link #flushPendingRescans} 执行。</p>
     */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> pendingRescans = new Object2ObjectOpenHashMap<>();

    private ContainerChunkCache() {}

    /** 获取单例实例 */
    public static ContainerChunkCache getInstance() {
        return INSTANCE;
    }

    /**
     * 区块加载事件 —— 扫描新加载的区块，若有容器则加入缓存。
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level
            && event.getChunk() instanceof LevelChunk chunk) {
            scanChunkForContainers(level, chunk);
        }
    }

    /**
     * 区块卸载事件 —— 不立即从缓存中移除。
     *
     * 为什么不在卸载时移除：
     *   ChunkEvent.Load 在 waitUntilNextTick() 的 runAllTasks() 中触发，
     *   而 processLevelContainers 在 ServerTickEvent.Post 中执行（早于 runAllTasks）。
     *   如果卸载时移除，重新加载时 ChunkEvent.Load 来不及在同一 tick 加回缓存，
     *   导致 processLevelContainers 找不到该区块。
     *
     * 改为由 cleanupStaleEntries 定期清理已卸载的区块（兜底机制）。
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        // 不立即移除，由 cleanupStaleEntries 负责清理
    }

    /**
     * 方块放置事件 —— 安排该区块在下一 tick 重扫。
     *
     * <p>不在此处判断是否为容器方块：{@code EntityPlaceEvent} 触发时 BlockEntity
     * 可能还没就绪，capability 查询会返回 null 从而误判为非容器。
     * 是否真的有容器交由 {@link #scanChunkForContainers} 在下一 tick 判定。</p>
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            schedulePendingRescan(level, event.getPos());
        }
    }

    /**
     * 方块破坏事件 —— 清理该位置的容器级数据，并安排区块在下一 tick 重扫。
     *
     * <p>同样不做 capability 前置判断：破坏时 capability 可能已失效，
     * 前置守卫会导致缓存残留失效条目。</p>
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ContainerLivingItemHandler.removeDataByPos(level, event.getPos());
            schedulePendingRescan(level, event.getPos());
        }
    }

    private void schedulePendingRescan(ServerLevel level, BlockPos pos) {
        pendingRescans.computeIfAbsent(level.dimension(), k -> new ObjectOpenHashSet<>())
                      .add(new ChunkPos(pos));
    }

    /**
     * 执行本 tick 累积的延后重扫（由 tick 循环在处理容器前调用）。
     *
     * @param level 服务端世界
     */
    public void flushPendingRescans(ServerLevel level) {
        Set<ChunkPos> pending = pendingRescans.remove(level.dimension());
        if (pending == null || pending.isEmpty()) return;

        for (ChunkPos cPos : pending) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cPos.x, cPos.z);
            if (chunk != null) {
                scanChunkForContainers(level, chunk);
            }
        }
    }

    /**
     * 扫描指定区块，检查是否包含容器方块实体（通过 IItemHandler 能力），更新缓存。
     *
     * @param level 服务端世界
     * @param chunk 要扫描的区块
     */
    private void scanChunkForContainers(ServerLevel level, LevelChunk chunk) {
        ResourceKey<Level> dim = level.dimension();
        ChunkPos pos = chunk.getPos();

        boolean hasContainer = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null) != null) {
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
     * 获取指定维度中包含容器的区块坐标集合的快照副本。
     *
     * 返回副本而非原始集合的视图，确保调用方在遍历时不会被事件回调
     * （如区块加载/卸载）对底层集合的修改影响，避免 ConcurrentModification。
     *
     * @param dim 维度 Key
     * @return 区块坐标集合的快照副本；如果该维度没有缓存则返回空集合
     */
    public Set<ChunkPos> getCachedChunks(ResourceKey<Level> dim) {
        Set<ChunkPos> raw = chunkCache.get(dim);
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        return new java.util.HashSet<>(raw);
    }

    /** 清空所有缓存（用于服务端关闭或维度卸载等场景） */
    public void clear() {
        chunkCache.clear();
        lastCleanupTick.clear();
        pendingRescans.clear();
    }

    /** 从缓存中移除指定区块（自清洁，由 tick 循环调用） */
    public void removeChunk(ResourceKey<Level> dim, ChunkPos pos) {
        Set<ChunkPos> chunkSet = chunkCache.get(dim);
        if (chunkSet != null && chunkSet.remove(pos)) {
            PerfMetrics.recordCacheSelfClean();
        }
    }

    /**
     * 定期清理缓存中已不再加载的区块。
     *
     * 与 onChunkUnload 不同，此方法作为兜底机制，防止因异步卸载时序问题
     * 导致已卸载区块残留在缓存中。每隔 intervalTicks 执行一次清理。
     *
     * @param level         服务端世界
     * @param intervalTicks 清理间隔（tick）
     */
    public void cleanupStaleEntries(ServerLevel level, int intervalTicks) {
        ResourceKey<Level> dim = level.dimension();
        long gameTime = level.getGameTime();

        Long lastCleanup = lastCleanupTick.get(dim);
        if (lastCleanup == null) {
            lastCleanupTick.put(dim, gameTime);
            return;
        }
        if (gameTime - lastCleanup < intervalTicks) {
            return;
        }
        lastCleanupTick.put(dim, gameTime);

        Set<ChunkPos> chunkSet = chunkCache.get(dim);
        if (chunkSet == null || chunkSet.isEmpty()) return;

        var toRemove = new java.util.ArrayList<ChunkPos>();
        for (ChunkPos pos : chunkSet) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
                toRemove.add(pos);
            }
        }
        for (ChunkPos pos : toRemove) {
            chunkSet.remove(pos);
        }
        if (!toRemove.isEmpty()) {
            ModLog.CONTAINER.debug("cleanupStaleEntries removed {} chunks in {}", toRemove.size(), dim.location());
        }
    }

    public int getCacheSize(ResourceKey<Level> dim) {
        Set<ChunkPos> chunkSet = chunkCache.get(dim);
        return chunkSet == null ? 0 : chunkSet.size();
    }

    public void logCacheStats() {
        StringBuilder sb = new StringBuilder("ContainerChunkCache stats:");
        for (var entry : chunkCache.entrySet()) {
            sb.append(" ").append(entry.getKey().location()).append("=").append(entry.getValue().size());
        }
        ModLog.CONTAINER.debug(sb.toString());
    }
}