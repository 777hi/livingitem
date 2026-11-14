package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** HIGH/SUPER raw section 写必须复用原版 POI 状态变更入口。 */
class ExplosionPoiCleanupTest {
    record Change(BlockPos pos, BlockState oldState, BlockState newState) {}

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
        final LevelChunk chunk;
        final List<Change> changes = new ArrayList<>();

        Fixture() throws ReflectiveOperationException {
            MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
            biomes.register(Biomes.PLAINS, mock(Biome.class), RegistrationInfo.BUILT_IN);
            biomes.freeze();
            RegistryAccess access = mock(RegistryAccess.class);
            when(access.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
            when(level.registryAccess()).thenReturn(access);
            when(level.getMinBuildHeight()).thenReturn(-64);
            when(level.getHeight()).thenReturn(384);
            when(level.getMaxBuildHeight()).thenReturn(320);
            when(level.getMinSection()).thenReturn(-4);
            when(level.getMaxSection()).thenReturn(20);
            when(level.getSectionsCount()).thenReturn(24);
            MinecraftServer server = mock(MinecraftServer.class);
            doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(server).execute(any(Runnable.class));
            when(level.getServer()).thenReturn(server);
            chunk = new LevelChunk(level, new ChunkPos(0, 0));
            when(level.getBlockState(any(BlockPos.class))).thenAnswer(inv -> chunk.getBlockState(inv.getArgument(0)));
            when(level.getFluidState(any(BlockPos.class))).thenAnswer(inv -> chunk.getFluidState(inv.getArgument(0)));
            doAnswer(inv -> {
                BlockPos pos = inv.getArgument(0);
                assertTrue(chunk.getBlockState(pos).isAir(), "POI hook 必须发生在 raw AIR 写入后");
                changes.add(new Change(pos.immutable(), inv.getArgument(1), inv.getArgument(2)));
                return null;
            }).when(level).onBlockStateChange(any(BlockPos.class), any(BlockState.class), any(BlockState.class));
            when(level.getLightEngine()).thenReturn(mock(LevelLightEngine.class));
            ServerChunkCache source = mock(ServerChunkCache.class);
            ChunkMap map = mock(ChunkMap.class);
            Field field = ServerChunkCache.class.getDeclaredField("chunkMap");
            field.setAccessible(true);
            field.set(source, map);
            when(level.getChunkSource()).thenReturn(source);
            when(map.getPlayers(chunk.getPos(), false)).thenReturn(List.of());
        }

        void set(BlockPos pos, BlockState state) {
            chunk.getSection(chunk.getSectionIndex(pos.getY()))
                .setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        }
    }

    @Test
    void highNotifiesOnlyDestroyedPoiAndNeverRepeats() throws Exception {
        assertTrue(net.minecraft.world.entity.ai.village.poi.PoiTypes
            .forState(Blocks.FLETCHING_TABLE.defaultBlockState()).isPresent());
        Fixture f = new Fixture();
        BlockPos center = new BlockPos(8, 32, 8);
        BlockPos ordinary = new BlockPos(7, 32, 8);
        BlockPos outside = new BlockPos(15, 32, 15);
        BlockPos resistant = new BlockPos(9, 32, 8);
        f.set(center, Blocks.FLETCHING_TABLE.defaultBlockState());
        f.set(ordinary, Blocks.STONE.defaultBlockState());
        f.set(outside, Blocks.FLETCHING_TABLE.defaultBlockState());
        f.set(resistant, Blocks.OBSIDIAN.defaultBlockState());
        ExplosionParams params = new ExplosionParams(8.5, 32.5, 8.5, 2,
            ExplosionParams.Mode.HIGH_YIELD, false);

        ExplosionComponent.applyToChunk(f.level, params, f.chunk);
        ExplosionComponent.applyToChunk(f.level, params, f.chunk);

        assertEquals(List.of(new Change(center, Blocks.FLETCHING_TABLE.defaultBlockState(), Blocks.AIR.defaultBlockState())),
            f.changes);
        assertEquals(Blocks.FLETCHING_TABLE.defaultBlockState(), f.chunk.getBlockState(outside));
        assertEquals(Blocks.OBSIDIAN.defaultBlockState(), f.chunk.getBlockState(resistant));
    }

    @Test
    void superNotifiesEveryPoiButNoOrdinaryBlocksOrSecondPass() throws Exception {
        Fixture f = new Fixture();
        BlockPos first = new BlockPos(1, 20, 2);
        BlockPos second = new BlockPos(5, 50, 6);
        f.set(first, Blocks.FLETCHING_TABLE.defaultBlockState());
        f.set(second, Blocks.BELL.defaultBlockState());
        f.set(new BlockPos(7, 30, 8), Blocks.STONE.defaultBlockState());
        ExplosionParams params = new ExplosionParams(8.5, 32.5, 8.5, 1000,
            ExplosionParams.Mode.SUPER, false);

        ExplosionComponent.applyToChunk(f.level, params, f.chunk);
        ExplosionComponent.applyToChunk(f.level, params, f.chunk);

        assertEquals(2, f.changes.size());
        assertTrue(f.changes.stream().anyMatch(c -> c.pos().equals(first)
            && c.oldState().is(Blocks.FLETCHING_TABLE) && c.newState().isAir()));
        assertTrue(f.changes.stream().anyMatch(c -> c.pos().equals(second)
            && c.oldState().is(Blocks.BELL) && c.newState().isAir()));
    }
}
