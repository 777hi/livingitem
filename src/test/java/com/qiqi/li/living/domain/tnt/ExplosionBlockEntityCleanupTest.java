package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;
import com.qiqi.li.living.container.PendingBlockEntityAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** 批量爆炸清除 live 与 packed 方块实体数据，且不得为清理触发实例化。 */
class ExplosionBlockEntityCleanupTest {

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
        final LevelChunk chunk;

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
            chunk = spy(new LevelChunk(level, new ChunkPos(0, 0)));
            when(level.getBlockState(any(BlockPos.class))).thenAnswer(inv -> chunk.getBlockState(inv.getArgument(0)));
            when(level.getFluidState(any(BlockPos.class))).thenAnswer(inv -> chunk.getFluidState(inv.getArgument(0)));
            LevelLightEngine light = mock(LevelLightEngine.class);
            when(level.getLightEngine()).thenReturn(light);
            ServerChunkCache source = mock(ServerChunkCache.class);
            ChunkMap map = mock(ChunkMap.class);
            var field = ServerChunkCache.class.getDeclaredField("chunkMap");
            field.setAccessible(true);
            field.set(source, map);
            when(level.getChunkSource()).thenReturn(source);
            when(map.getPlayers(chunk.getPos(), false)).thenReturn(List.of());
            chunk.setLoaded(true);
        }

        void block(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            chunk.getSection(chunk.getSectionIndex(pos.getY()))
                .setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        }

        void pending(BlockPos pos) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "DUMMY");
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            chunk.setBlockEntityNbt(tag);
        }
    }

    @Test
    void accessorIsAppliedAndPendingCleanupDoesNotPromote() throws Exception {
        Fixture f = new Fixture();
        BlockPos pos = new BlockPos(1, 32, 2);
        f.pending(pos);
        assertTrue(f.chunk instanceof PendingBlockEntityAccess);

        ExplosionComponent.BlockEntityRemoval removal = ExplosionComponent.removeBlockEntityData(f.chunk, pos);
        assertFalse(removal.live());
        assertTrue(removal.pending());

        assertNull(f.chunk.getBlockEntityNbt(pos));
        assertFalse(f.chunk.getBlockEntitiesPos().contains(pos));
        verify(f.chunk, never()).getBlockEntity(any(BlockPos.class));
    }

    @Test
    void liveAndPendingAtSamePositionUseVanillaLiveRemoval() throws Exception {
        Fixture f = new Fixture();
        BlockPos pos = new BlockPos(2, 32, 3);
        f.block(pos, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        f.chunk.setBlockEntity(chest);
        f.pending(pos);

        ExplosionComponent.BlockEntityRemoval removal = ExplosionComponent.removeBlockEntityData(f.chunk, pos);
        assertTrue(removal.live());
        assertTrue(removal.pending());

        assertTrue(chest.isRemoved());
        assertFalse(f.chunk.getBlockEntities().containsKey(pos));
        assertNull(f.chunk.getBlockEntityNbt(pos));
        verify(f.chunk, never()).getBlockEntity(any(BlockPos.class));
    }

    @Test
    void highYieldClearsOnlyActuallyDestroyedBlockEntityPositions() throws Exception {
        Fixture f = new Fixture();
        BlockPos hit = new BlockPos(8, 32, 8);
        BlockPos outside = new BlockPos(15, 32, 15);
        BlockPos resistant = new BlockPos(9, 32, 8);
        f.block(hit, Blocks.CHEST.defaultBlockState());
        f.block(outside, Blocks.CHEST.defaultBlockState());
        f.block(resistant, Blocks.OBSIDIAN.defaultBlockState());
        f.pending(hit);
        f.pending(outside);
        f.pending(resistant);

        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(8, 32, 8, 2, ExplosionParams.Mode.HIGH_YIELD, false), f.chunk);

        assertNull(f.chunk.getBlockEntityNbt(hit));
        assertNotNull(f.chunk.getBlockEntityNbt(outside));
        assertNotNull(f.chunk.getBlockEntityNbt(resistant));
        verify(f.chunk, never()).getBlockEntity(any(BlockPos.class));
    }

    @Test
    void superClearsPendingOnlyAirChunkAndDoesNotTreatItAsNoOp() throws Exception {
        Fixture f = new Fixture();
        BlockPos first = new BlockPos(1, 32, 2);
        BlockPos second = new BlockPos(4, -20, 5);
        f.pending(first);
        f.pending(second);
        f.chunk.setUnsaved(false);

        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(8, 32, 8, 1000, ExplosionParams.Mode.SUPER, false), f.chunk);

        assertTrue(f.chunk.getBlockEntitiesPos().isEmpty());
        assertNull(f.chunk.getBlockEntityNbt(first));
        assertNull(f.chunk.getBlockEntityNbt(second));
        assertTrue(f.chunk.isUnsaved());
        verify(f.chunk, never()).getBlockEntity(any(BlockPos.class));
    }
}
