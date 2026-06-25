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

public class ContainerChunkCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ContainerChunkCache INSTANCE = new ContainerChunkCache();

    private final Map<ResourceKey<Level>, Set<ChunkPos>> chunkCache = new Object2ObjectOpenHashMap<>();

    private ContainerChunkCache() {}

    public static ContainerChunkCache getInstance() {
        return INSTANCE;
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPos pos = event.getChunk().getPos();
            if (level.hasChunk(pos.x, pos.z)) {
                scanChunkForContainers(level, level.getChunk(pos.x, pos.z));
            }
        }
    }

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

    public Set<ChunkPos> getCachedChunks(ResourceKey<Level> dim) {
        return chunkCache.getOrDefault(dim, Collections.emptySet());
    }

    public void clear() {
        chunkCache.clear();
    }
}