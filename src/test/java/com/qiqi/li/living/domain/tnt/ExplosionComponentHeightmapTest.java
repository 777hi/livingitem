package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** 批量破坏的真实高度图守卫；高度查询、packed 保存与发包数据必须一致。 */
class ExplosionComponentHeightmapTest {
    private static final EnumSet<Heightmap.Types> TYPES = EnumSet.of(
        Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
        final LevelChunk chunk;
        LevelLightEngine lightEngine;
        ChunkMap chunkMap;

        Fixture(int minY, int height) {
            this(minY, height, false);
        }

        Fixture(int minY, int height, boolean measure) {
            MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
            biomes.register(Biomes.PLAINS, mock(Biome.class), RegistrationInfo.BUILT_IN);
            biomes.freeze();
            RegistryAccess access = mock(RegistryAccess.class);
            when(access.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
            when(level.registryAccess()).thenReturn(access);
            when(level.getMinBuildHeight()).thenReturn(minY);
            when(level.getHeight()).thenReturn(height);
            when(level.getMaxBuildHeight()).thenReturn(minY + height);
            when(level.getMinSection()).thenReturn(minY >> 4);
            when(level.getMaxSection()).thenReturn((minY + height) >> 4);
            when(level.getSectionsCount()).thenReturn(height >> 4);
            chunk = measure ? new MeasureChunk(level, minY, height) : new LevelChunk(level, new ChunkPos(3, -2));
            when(level.getBlockState(any(BlockPos.class))).thenAnswer(inv -> chunk.getBlockState(inv.getArgument(0)));
            when(level.getFluidState(any(BlockPos.class))).thenAnswer(inv -> chunk.getFluidState(inv.getArgument(0)));
        }

        void put(int x, int y, int z, BlockState state) {
            raw(x, y, z, state);
            for (Heightmap.Types type : TYPES) chunk.getOrCreateHeightmapUnprimed(type).update(x, y, z, state);
        }

        void raw(int x, int y, int z, BlockState state) {
            chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state, false);
        }

        void enableNotifications() throws ReflectiveOperationException {
            lightEngine = mock(LevelLightEngine.class);
            when(level.getLightEngine()).thenReturn(lightEngine);
            ServerChunkCache source = mock(ServerChunkCache.class);
            chunkMap = mock(ChunkMap.class);
            // Mockito 不桩字段；测试替身的 final chunkMap 用真实字段接入发包路径。
            Field field = ServerChunkCache.class.getDeclaredField("chunkMap");
            field.setAccessible(true);
            field.set(source, chunkMap);
            when(level.getChunkSource()).thenReturn(source);
            when(chunkMap.getPlayers(chunk.getPos(), false)).thenReturn(List.of());
        }
    }

    /** 量测夹具绕过 Mockito 的世界调用；仍使用真实 section、高度图和修复方法。 */
    private static final class MeasureChunk extends LevelChunk {
        private final int minY;
        private final int height;

        MeasureChunk(ServerLevel level, int minY, int height) {
            super(level, new ChunkPos(3, -2));
            this.minY = minY;
            this.height = height;
        }

        @Override
        public int getMinBuildHeight() {
            return height == 0 ? super.getMinBuildHeight() : minY;
        }

        @Override
        public int getHeight() {
            return height == 0 ? super.getHeight() : height;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int section = (pos.getY() - minY) >> 4;
            if (section < 0 || section >= getSections().length) return Blocks.AIR.defaultBlockState();
            return getSection(section).getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    private static boolean[] columns(int... indices) {
        boolean[] changed = new boolean[256];
        for (int index : indices) changed[index] = true;
        return changed;
    }

    private static void assertHeight(LevelChunk chunk, int x, int z, int expected) {
        for (Heightmap.Types type : TYPES) {
            assertEquals(expected, chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(x, z), type.toString());
            assertEquals(expected - 1, chunk.getHeight(type, x, z), type + " highest taken");
        }
    }

    @Test
    void superResetsAllColumnsForNegativeAndCustomWorldHeights() {
        for (int[] world : new int[][] { {-64, 384}, {0, 256}, {-128, 1024} }) {
            Fixture f = new Fixture(world[0], world[1]);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) f.put(x, world[0] + world[1] - 2, z, Blocks.STONE.defaultBlockState());
            }
            int words = f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getRawData().length;
            ExplosionComponent.resetHeightmaps(f.chunk);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertHeight(f.chunk, x, z, world[0]);
            for (Heightmap.Types type : TYPES) {
                assertArrayEquals(new long[words], f.chunk.getOrCreateHeightmapUnprimed(type).getRawData());
            }
        }
    }

    @Test
    void superRepairsAlreadyEmptySectionsWithStaleHeightmaps() {
        Fixture f = new Fixture(-64, 384);
        f.put(1, 100, 2, Blocks.STONE.defaultBlockState());
        f.raw(1, 100, 2, Blocks.AIR.defaultBlockState());
        assertTrue(f.chunk.getSection(f.chunk.getSectionIndex(100)).hasOnlyAir());
        assertHeight(f.chunk, 1, 2, 101);
        ExplosionComponent.resetHeightmaps(f.chunk);
        assertHeight(f.chunk, 1, 2, -64);
    }

    @Test
    void resetHeightmapsReportsOnlyActualRepair() {
        Fixture f = new Fixture(-64, 384);
        assertFalse(ExplosionComponent.resetHeightmaps(f.chunk));
        f.put(1, 100, 2, Blocks.STONE.defaultBlockState());
        f.raw(1, 100, 2, Blocks.AIR.defaultBlockState());
        assertTrue(ExplosionComponent.resetHeightmaps(f.chunk));
        assertFalse(ExplosionComponent.resetHeightmaps(f.chunk));
    }

    @Test
    void repeatedSuperOnCleanEmptyChunkSkipsDirtyPacketAndLight() throws ReflectiveOperationException {
        Fixture f = new Fixture(-64, 384);
        f.enableNotifications();
        f.chunk.setUnsaved(false);

        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(56, 64, -24, 1000, ExplosionParams.Mode.SUPER, false), f.chunk);

        assertFalse(f.chunk.isUnsaved());
        verifyNoInteractions(f.lightEngine);
        verify(f.chunkMap, never()).getPlayers(any(ChunkPos.class), anyBoolean());
    }

    @Test
    void superWithOnlyStaleHeightmapSyncsWithoutRefreshingLight() throws ReflectiveOperationException {
        Fixture f = new Fixture(-64, 384);
        f.put(1, 100, 2, Blocks.STONE.defaultBlockState());
        f.raw(1, 100, 2, Blocks.AIR.defaultBlockState());
        assertTrue(f.chunk.getSection(f.chunk.getSectionIndex(100)).hasOnlyAir());
        f.enableNotifications();
        f.chunk.setUnsaved(false);

        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(56, 64, -24, 1000, ExplosionParams.Mode.SUPER, false), f.chunk);

        assertTrue(f.chunk.isUnsaved());
        assertHeight(f.chunk, 1, 2, -64);
        verify(f.chunkMap).getPlayers(f.chunk.getPos(), false);
        verify(f.lightEngine, never()).updateSectionStatus(any(SectionPos.class), anyBoolean());
        verify(f.lightEngine, never()).propagateLightSources(any(ChunkPos.class));
        verify(f.lightEngine, never()).checkBlock(any(BlockPos.class));
    }

    @Test
    void superWithContentStillSyncsAndRefreshesLight() throws ReflectiveOperationException {
        Fixture f = new Fixture(-64, 384);
        f.put(1, 100, 2, Blocks.STONE.defaultBlockState());
        f.enableNotifications();
        f.chunk.setUnsaved(false);

        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(56, 64, -24, 1000, ExplosionParams.Mode.SUPER, false), f.chunk);

        assertTrue(f.chunk.isUnsaved());
        verify(f.chunkMap).getPlayers(f.chunk.getPos(), false);
        verify(f.lightEngine, atLeastOnce()).updateSectionStatus(any(SectionPos.class), anyBoolean());
        verify(f.lightEngine).propagateLightSources(f.chunk.getPos());
    }

    @Test
    void highRepairsDeletedTopsAcrossSectionsAndLeavesUnaffectedColumns() {
        Fixture f = new Fixture(-64, 384);
        f.put(1, -20, 2, Blocks.STONE.defaultBlockState());
        f.put(1, 17, 2, Blocks.STONE.defaultBlockState());
        f.put(1, 80, 2, Blocks.STONE.defaultBlockState());
        f.put(4, 90, 5, Blocks.STONE.defaultBlockState());
        f.raw(1, 80, 2, Blocks.AIR.defaultBlockState());
        f.raw(1, 17, 2, Blocks.AIR.defaultBlockState());
        ExplosionComponent.updateHeightmaps(f.chunk, columns(1 + 2 * 16));
        assertHeight(f.chunk, 1, 2, -19);
        assertHeight(f.chunk, 4, 5, 91);
        f.raw(1, -20, 2, Blocks.AIR.defaultBlockState());
        ExplosionComponent.updateHeightmaps(f.chunk, columns(1 + 2 * 16));
        assertHeight(f.chunk, 1, 2, -64);
    }

    @Test
    void highPreservesSurvivingOpaqueTopWhenOnlyLowerBlocksWereRemoved() {
        Fixture f = new Fixture(-64, 384);
        f.put(2, 0, 3, Blocks.STONE.defaultBlockState());
        f.put(2, 80, 3, Blocks.OBSIDIAN.defaultBlockState());
        f.raw(2, 0, 3, Blocks.AIR.defaultBlockState());
        ExplosionComponent.updateHeightmaps(f.chunk, columns(2 + 3 * 16));
        assertHeight(f.chunk, 2, 3, 81);
    }

    @Test
    void highUsesVanillaWaterLeavesAndSolidPredicatesIndependently() {
        Fixture f = new Fixture(-64, 384);
        f.put(2, 0, 3, Blocks.STONE.defaultBlockState());
        f.put(2, 10, 3, Blocks.WATER.defaultBlockState());
        f.put(2, 20, 3, Blocks.OAK_LEAVES.defaultBlockState());
        f.put(2, 30, 3, Blocks.STONE.defaultBlockState());
        f.raw(2, 30, 3, Blocks.AIR.defaultBlockState());
        ExplosionComponent.updateHeightmaps(f.chunk, columns(2 + 3 * 16));
        assertEquals(21, f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(2, 3));
        assertEquals(21, f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).getFirstAvailable(2, 3));
        assertEquals(11, f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).getFirstAvailable(2, 3));
        assertEquals(21, f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).getFirstAvailable(2, 3));
        f.raw(2, 20, 3, Blocks.AIR.defaultBlockState());
        ExplosionComponent.updateHeightmaps(f.chunk, columns(2 + 3 * 16));
        assertEquals(11, f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(2, 3));
        assertEquals(1, f.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).getFirstAvailable(2, 3));
    }

    @Test
    void packedNbtRoundTripAndClientPacketCarryRepairedHeights() {
        Fixture f = new Fixture(-64, 384);
        f.put(3, 40, 4, Blocks.STONE.defaultBlockState());
        f.put(3, 90, 4, Blocks.STONE.defaultBlockState());
        f.raw(3, 90, 4, Blocks.AIR.defaultBlockState());
        ExplosionComponent.updateHeightmaps(f.chunk, columns(3 + 4 * 16));
        CompoundTag saved = new CompoundTag();
        for (Heightmap.Types type : TYPES) {
            saved.putLongArray(type.getSerializationKey(), f.chunk.getOrCreateHeightmapUnprimed(type).getRawData());
        }
        Fixture restored = new Fixture(-64, 384);
        for (Heightmap.Types type : TYPES) restored.chunk.setHeightmap(type, saved.getLongArray(type.getSerializationKey()));
        assertHeight(restored.chunk, 3, 4, 41);
        CompoundTag packet = new ClientboundLevelChunkPacketData(f.chunk).getHeightmaps();
        for (Heightmap.Types type : TYPES) {
            assertEquals(type.sendToClient(), packet.contains(type.getSerializationKey()));
            if (type.sendToClient()) assertArrayEquals(saved.getLongArray(type.getSerializationKey()), packet.getLongArray(type.getSerializationKey()));
        }
    }

    @Test
    void actualSuperPathRepairsHeightBeforeConstructingChunkPacket() throws ReflectiveOperationException {
        Fixture f = new Fixture(-64, 384);
        f.put(3, 90, 4, Blocks.STONE.defaultBlockState());
        f.enableNotifications();
        LevelChunk observed = spy(f.chunk);
        AtomicBoolean packetRead = new AtomicBoolean();
        doAnswer(inv -> {
            assertHeight(observed, 3, 4, -64);
            packetRead.set(true);
            return inv.callRealMethod();
        }).when(observed).getHeightmaps();
        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(56, 90, -24, 1000, ExplosionParams.Mode.SUPER, false), observed);
        assertTrue(packetRead.get(), "actual packet construction must observe repaired heights");
        assertTrue(observed.getBlockState(new BlockPos(51, 90, -28)).isAir());
    }

    @Test
    void actualHighPathRepairsDeletedTopAndRetainsResistantGround() throws ReflectiveOperationException {
        Fixture f = new Fixture(-64, 384);
        f.put(8, 0, 8, Blocks.OBSIDIAN.defaultBlockState());
        f.put(8, 30, 8, Blocks.STONE.defaultBlockState());
        f.enableNotifications();
        ExplosionComponent.applyToChunk(f.level,
            new ExplosionParams(56, 30, -24, 40, ExplosionParams.Mode.HIGH_YIELD, false), f.chunk);
        assertHeight(f.chunk, 8, 8, 1);
        assertTrue(f.chunk.getBlockState(new BlockPos(56, 30, -24)).isAir());
        assertEquals(Blocks.OBSIDIAN.defaultBlockState(), f.chunk.getBlockState(new BlockPos(56, 0, -24)));
    }

    @Tag("perf")
    @Test
    void measureHeightmapRepairCost() {
        Fixture f = new Fixture(-64, 384, true);
        boolean[] sparse = columns(0);
        boolean[] dense = new boolean[256];
        java.util.Arrays.fill(dense, true);
        for (int column = 0; column < 256; column++) {
            f.put(column & 15, -20, column >> 4, Blocks.STONE.defaultBlockState());
            f.put(column & 15, 200, column >> 4, Blocks.STONE.defaultBlockState());
            f.raw(column & 15, 200, column >> 4, Blocks.AIR.defaultBlockState());
        }
        List<long[]> original = new ArrayList<>();
        for (Heightmap.Types type : TYPES) original.add(f.chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone());
        for (boolean[] changed : new boolean[][] { sparse, dense }) {
            long nanos = 0;
            for (int iteration = 0; iteration < 200; iteration++) {
                int index = 0;
                for (Heightmap.Types type : TYPES) f.chunk.setHeightmap(type, original.get(index++));
                long start = System.nanoTime();
                ExplosionComponent.updateHeightmaps(f.chunk, changed);
                long elapsed = System.nanoTime() - start;
                if (iteration >= 50) nanos += elapsed;
            }
            System.out.printf("heightmap HIGH %s: %.3f ms/chunk%n", changed == sparse ? "1 column" : "256 columns", nanos / 150.0 / 1_000_000.0);
        }
        long nanos = 0;
        for (int iteration = 0; iteration < 2000; iteration++) {
            long start = System.nanoTime();
            ExplosionComponent.resetHeightmaps(f.chunk);
            long elapsed = System.nanoTime() - start;
            if (iteration >= 500) nanos += elapsed;
        }
        System.out.printf("heightmap SUPER: %.3f ms/chunk%n", nanos / 1500.0 / 1_000_000.0);
    }
}
