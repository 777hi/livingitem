package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerFluidHandler;
import com.qiqi.li.living.domain.water.FluidFlowBehavior;
import com.qiqi.li.living.domain.water.FluidFlowBehaviors;
import com.qiqi.li.living.domain.water.FluidFlowClientCache;
import com.qiqi.li.living.domain.water.FluidFlowServerSync;
import com.qiqi.li.living.domain.water.FluidFlowSyncPacket;
import com.qiqi.li.living.domain.water.WaterComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** 区块容器扫描回归：战利品容器保留、真实附件重载后的发现与流体渲染恢复。 */
class LivingItemChunkContainerScanTest {

    private static final ResourceKey<LootTable> LOOT = ResourceKey.create(
        Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/shipwreck_map"));
    private static final HolderLookup.Provider REGISTRIES = RegistryAccess.EMPTY;
    private static final BlockPos CHEST_POS = new BlockPos(1, 64, 2);
    private static final int SOURCE_SLOT = 13;

    private final Map<FluidType, FluidFlowBehavior> savedBehaviors = new IdentityHashMap<>();

    @BeforeEach
    void setUp() {
        clearRuntimeCaches();
        for (FluidType fluid : NeoForgeRegistries.FLUID_TYPES) {
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fluid);
            if (behavior != FluidFlowBehavior.STATIC) savedBehaviors.put(fluid, behavior);
        }
        FluidFlowBehaviors.clear();
        // 单源空箱只需扩散行为；节拍仍由原版水 / 岩浆的 getTickDelay 决定。
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, -1));
        FluidFlowBehaviors.register(Fluids.LAVA.getFluidType(), FluidFlowBehavior.flowing(3, -1));
    }

    @AfterEach
    void tearDown() {
        clearRuntimeCaches();
        FluidFlowBehaviors.clear();
        savedBehaviors.forEach(FluidFlowBehaviors::register);
    }

    private static void clearRuntimeCaches() {
        ContainerChunkCache.getInstance().clear();
        ContainerLivingItemHandler.clearAllCaches();
        FluidFlowServerSync.clearRuntimeState();
    }

    private static Stream<Arguments> fluids() {
        return Stream.of(
            Arguments.of("水", Fluids.WATER.getFluidType()),
            Arguments.of("岩浆", Fluids.LAVA.getFluidType()));
    }

    @Test
    @DisplayName("R-04：未开箱的战利品容器不处理、不查能力，但区块必须保留（返回 0 而非 -1）")
    void unopenedLootChest_retainsChunkWithoutCapabilityQuery() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        LevelChunk chunk = Mockito.mock(LevelChunk.class);
        BlockPos pos = new BlockPos(1, 64, 2);
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLootTable(LOOT, 0L);
        Mockito.when(chunk.getBlockEntities()).thenReturn(Map.of(pos, chest));

        int result = LivingItem.processChunkContainers(
            level, chunk, new IdentityHashMap<IItemHandler, Boolean>(), new HashSet<>());

        assertEquals(0, result,
            "0 = 没有可处理的容器，但区块内确有容器 ⇒ 不能返回 -1（否则区块被移出缓存）");
        Mockito.verifyNoInteractions(level);
    }

    @Test
    @DisplayName("开箱后 lootTable 已置空：回到普通能力查询路径")
    void openedLootChest_fallsThroughToCapability() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        LevelChunk chunk = Mockito.mock(LevelChunk.class);
        BlockPos pos = new BlockPos(1, 64, 2);
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLootTable(null); // 原版 unpackLootTable 末行
        Mockito.when(chunk.getBlockEntities()).thenReturn(Map.of(pos, chest));

        int result = LivingItem.processChunkContainers(
            level, chunk, new IdentityHashMap<IItemHandler, Boolean>(), new HashSet<>());

        assertEquals(-1, result, "没有 ItemHandler ⇒ 不是容器区块，应移出缓存");
        Mockito.verify(level).getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    @ParameterizedTest(name = "{0}：真实箱子 NBT 重载，首次区块不可用后无需第二次 Load 即恢复流体")
    @MethodSource("fluids")
    void savedFluid_resumesAfterInitiallyUnavailableChunk(String name, FluidType fluid) {
        ChestWorld world = new ChestWorld();
        ChestBlockEntity originalChest = world.chest;
        ContainerFluidData originalData = seedAndTick(world, fluid);
        CompoundTag saved = saveChest(originalChest);

        clearRuntimeCaches();
        assertNull(ContainerLivingItemHandler.peekContainerDataByPos(
            world.level, CHEST_POS, ContainerFluidData.KEY), "重载前必须清空旧坐标的运行时数据");
        world.reloadChest(saved);
        assertNotSame(originalChest, world.chest, "读回必须使用同坐标的新 BE，不能复用旧附件对象");
        assertTrue(world.chest.isEmpty(), "纯源箱子没有桶或其它活物品，恢复不能依赖物品激活");
        ContainerFluidData decoded = world.chest.getData(WaterComponents.CONTAINER_FLUID_DATA);
        assertNotSame(originalData, decoded, "附件必须经真实 CODEC 解码，不共享旧运行时实例");
        assertEquals(Map.of(SOURCE_SLOT, fluid), decoded.getGeneratedSources(), "源槽与流体类型必须落盘");
        assertTrue(decoded.getFlows().isEmpty(), "NBT 只存源，流动表必须由重载后的真实 tick 重建");

        resumeThroughDelayedLoad(world);

        ContainerFluidData restored = ContainerLivingItemHandler.peekContainerDataByPos(
            world.level, CHEST_POS, ContainerFluidData.KEY);
        assertNotNull(restored, "实际区块处理必须通过附件回填建立流体运行时数据");
        assertNotSame(originalData, restored, "旧 runtime cache 不能替 NBT 恢复兜底");
        assertEquals(Map.of(SOURCE_SLOT, fluid), restored.getGeneratedSources());
        assertTrue(restored.isSource(SOURCE_SLOT), "纯源容器必须进入自维持驱动");
        assertSame(fluid, restored.sourceFluid(SOURCE_SLOT), "岩浆重载后不能退化成水");
        assertSame(fluid, world.chest.getData(WaterComponents.CONTAINER_FLUID_DATA)
            .getGeneratedSources().get(SOURCE_SLOT), "恢复后的源仍写回新 BE 附件");

        ArgumentCaptor<FluidFlowSyncPacket> packets = ArgumentCaptor.forClass(FluidFlowSyncPacket.class);
        Mockito.verify(world.connection).send(packets.capture());
        FluidFlowSyncPacket packet = packets.getValue();
        var context = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, world.handler);
        assertNotNull(context);
        assertEquals(context.getCacheKey(), packet.containerKey(), "同步目标须与真实处理上下文同键");
        assertEquals(9, packet.width(), "27 槽箱子的渲染网格必须为 9 列");
        assertEquals(FluidFlowClientCache.RenderTarget.BLOCK, packet.target());
        ResourceLocation fluidId = NeoForgeRegistries.FLUID_TYPES.getKey(fluid);
        assertNotNull(fluidId);
        assertEquals(List.of(fluidId.toString()), packet.palette(), "快照必须保留可解析的流体调色板");
        assertFalse(packet.cells().isEmpty(), "不能只有持久源而没有客户端可渲染快照");
        assertArrayEquals(new int[]{0, -1, 0}, packet.cells().get(SOURCE_SLOT), "源格可渲染");
        assertArrayEquals(new int[]{1, SOURCE_SLOT, 0}, packet.cells().get(SOURCE_SLOT + 1),
            "首次恢复 tick 必须真的驱动蔓延，流动格及来向可渲染");
    }

    @ParameterizedTest(name = "{0}：最后一个源删除后保存再重载，不复活源或流体快照")
    @MethodSource("fluids")
    void removedLastSource_doesNotReviveAfterChestReload(String name, FluidType fluid) {
        ChestWorld world = new ChestWorld();
        ContainerFluidData data = seedAndTick(world, fluid);
        Mockito.clearInvocations(world.connection);

        data.removeGeneratedSource(SOURCE_SLOT);
        assertEquals(1, world.processChest(), "删源后仍须经过真实区块处理与附件写回");
        assertTrue(data.isEmpty(), "最后一个源删除后，旧流动表也必须被清空");
        assertSame(ContainerFluidData.EMPTY, world.chest.getData(WaterComponents.CONTAINER_FLUID_DATA),
            "源清空必须写回 EMPTY，不能留下旧的非空附件");
        ArgumentCaptor<FluidFlowSyncPacket> packets = ArgumentCaptor.forClass(FluidFlowSyncPacket.class);
        Mockito.verify(world.connection).send(packets.capture());
        assertTrue(packets.getValue().cells().isEmpty(), "删末源必须下发空快照清掉客户端残留");
        assertTrue(packets.getValue().palette().isEmpty());
        CompoundTag saved = saveChest(world.chest);

        clearRuntimeCaches();
        world.reloadChest(saved);
        assertTrue(world.chest.getData(WaterComponents.CONTAINER_FLUID_DATA).isEmpty(),
            "真实 NBT 读回不得带回删除前的源");
        resumeThroughDelayedLoad(world);

        ContainerFluidData restored = ContainerLivingItemHandler.peekContainerDataByPos(
            world.level, CHEST_POS, ContainerFluidData.KEY);
        assertTrue(restored == null || restored.isEmpty(), "实际处理重载箱子后仍不得复活源或流动格");
        assertTrue(world.chest.getData(WaterComponents.CONTAINER_FLUID_DATA).isEmpty());
        Mockito.verifyNoInteractions(world.connection);
    }

    /** 倒入单源后经真实区块处理落附件；不直接调用流体引擎或写回钩子。 */
    private static ContainerFluidData seedAndTick(ChestWorld world, FluidType fluid) {
        var context = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, world.handler);
        assertNotNull(context);
        assertEquals(27, context.getSize());
        assertEquals(9, context.getWidth());
        assertNull(context.getContainerUuid(), "原版箱子走 BE 附件，不走 UUID SavedData");
        ContainerFluidData data = ContainerFluidHandler.getOrCreateFluidData(context);
        data.registerGeneratedSource(SOURCE_SLOT, fluid);
        assertEquals(1, world.processChest());
        assertTrue(data.isSource(SOURCE_SLOT), "保存前必须已经由真实驱动建立源格");
        assertEquals(Map.of(SOURCE_SLOT, fluid),
            world.chest.getData(WaterComponents.CONTAINER_FLUID_DATA).getGeneratedSources(),
            "保存前必须已经由真实写回钩子落入 BE 附件");
        return data;
    }

    private static CompoundTag saveChest(ChestBlockEntity chest) {
        CompoundTag saved = chest.saveWithFullMetadata(REGISTRIES);
        assertEquals("minecraft:chest", saved.getString("id"));
        assertEquals(CHEST_POS, BlockEntity.getPosFromTag(saved));
        assertTrue(saved.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY)
            .contains(WaterComponents.CONTAINER_FLUID_DATA.getId().toString(), Tag.TAG_LIST),
            "真实 BE NBT 必须包含经 CODEC 序列化的流体附件，不能静默跳过序列化失败");
        return saved;
    }

    /** 仅登记一次 Load；首次非阻塞查询为空，后续可用时必须由待重扫队列自行恢复。 */
    private static void resumeThroughDelayedLoad(ChestWorld world) {
        ContainerChunkCache cache = ContainerChunkCache.getInstance();
        world.loaded.clear();
        ChunkEvent.Load event = new ChunkEvent.Load(world.chunk, true);
        Mockito.clearInvocations(world.level, world.chunk, world.source, world.connection);

        cache.onChunkLoad(event);
        Mockito.verify(world.level).dimension();
        Mockito.verifyNoMoreInteractions(world.level);
        Mockito.verify(world.chunk).getPos();
        Mockito.verifyNoMoreInteractions(world.chunk);
        Mockito.verifyNoInteractions(world.source, world.connection);

        assertEquals(0, world.tickDiscoveredChunks(), "首次 getChunkNow 为 null，不能提前处理箱子");
        assertEquals(0, cache.getCacheSize(Level.OVERWORLD));
        Mockito.verify(world.source).getChunkNow(world.chunkPos.x, world.chunkPos.z);
        Mockito.verify(world.level, Mockito.never()).getCapability(Capabilities.ItemHandler.BLOCK, CHEST_POS, null);
        assertNull(ContainerLivingItemHandler.peekContainerDataByPos(
            world.level, CHEST_POS, ContainerFluidData.KEY), "首个空查询不能回填流体或沿用旧 runtime cache");
        Mockito.verifyNoInteractions(world.connection);

        world.loaded.put(world.chunkPos.toLong(), world.chunk); // 不再触发 ChunkEvent.Load
        assertEquals(1, world.tickDiscoveredChunks(),
            "后续可用且没有第二次 Load：maintain 必须重试发现，processChunkContainers 必须实际处理");
        assertTrue(cache.getProcessableChunks(world.level).contains(world.chunkPos));
        Mockito.verify(world.source, Mockito.never())
            .getChunk(Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }

    /** 世界、区块可用性与网络是边界替身；槽位和附件均由真实 27 槽 ChestBlockEntity 持有。 */
    private static final class ChestWorld {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        final ChunkPos chunkPos = new ChunkPos(CHEST_POS);
        final Map<Long, LevelChunk> loaded = new HashMap<>();
        final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        final AtomicLong gameTime = new AtomicLong();
        final ServerPlayer viewer = Mockito.mock(ServerPlayer.class);
        final Inventory inventory = new Inventory(viewer);
        final ServerGamePacketListenerImpl connection = Mockito.mock(ServerGamePacketListenerImpl.class);
        ChestBlockEntity chest;
        IItemHandler handler;

        ChestWorld() {
            Mockito.when(level.dimension()).thenReturn(Level.OVERWORLD);
            var dimensionType = Mockito.mock(net.minecraft.world.level.dimension.DimensionType.class);
            Mockito.when(level.dimensionType()).thenReturn(dimensionType);
            Mockito.when(level.getGameTime()).thenAnswer(inv -> gameTime.get());
            Mockito.when(level.getChunkSource()).thenReturn(source);
            Mockito.when(level.getBlockEntity(Mockito.any(BlockPos.class)))
                .thenAnswer(inv -> blockEntities.get(inv.getArgument(0)));
            Mockito.when(level.getBlockState(Mockito.any(BlockPos.class)))
                .thenAnswer(inv -> CHEST_POS.equals(inv.getArgument(0))
                    ? Blocks.CHEST.defaultBlockState() : Blocks.AIR.defaultBlockState());
            Mockito.when(level.getCapability(Capabilities.ItemHandler.BLOCK, CHEST_POS, null))
                .thenAnswer(inv -> handler);
            Mockito.when(source.getChunkNow(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(inv -> loaded.get(ChunkPos.asLong(
                    inv.getArgument(0, Integer.class), inv.getArgument(1, Integer.class))));
            Mockito.when(source.isPositionTicking(Mockito.anyLong()))
                .thenAnswer(inv -> loaded.containsKey(inv.getArgument(0, Long.class)));
            Mockito.when(chunk.getLevel()).thenReturn(level);
            Mockito.when(chunk.getPos()).thenReturn(chunkPos);
            Mockito.when(chunk.getBlockEntities()).thenReturn(blockEntities);

            MinecraftServer server = Mockito.mock(MinecraftServer.class);
            PlayerList players = Mockito.mock(PlayerList.class);
            Mockito.when(level.getServer()).thenReturn(server);
            Mockito.when(server.getPlayerList()).thenReturn(players);
            Mockito.when(players.getPlayers()).thenReturn(List.of(viewer));
            Mockito.when(viewer.level()).thenReturn(level);
            Mockito.when(viewer.getInventory()).thenReturn(inventory);
            // 菜单仍绑定真实箱子槽位，但不触发开箱音效与开盖计数。
            Mockito.when(viewer.isSpectator()).thenReturn(true);
            viewer.connection = connection;
            installChest(new ChestBlockEntity(CHEST_POS, Blocks.CHEST.defaultBlockState()));
        }

        void reloadChest(CompoundTag saved) {
            ChestBlockEntity reloaded = new ChestBlockEntity(CHEST_POS, Blocks.CHEST.defaultBlockState());
            reloaded.loadWithComponents(saved, REGISTRIES);
            installChest(reloaded);
        }

        private void installChest(ChestBlockEntity current) {
            chest = current;
            chest.setLevel(level);
            blockEntities.put(CHEST_POS, chest);
            handler = new InvWrapper(chest);
            viewer.containerMenu = ChestMenu.threeRows(1, inventory, chest);
        }

        int processChest() {
            return LivingItem.processChunkContainers(level, chunk, new IdentityHashMap<>(), new HashSet<>());
        }

        int tickDiscoveredChunks() {
            gameTime.incrementAndGet();
            ContainerChunkCache cache = ContainerChunkCache.getInstance();
            cache.maintain(level);
            int processed = 0;
            for (ChunkPos pos : cache.getProcessableChunks(level)) {
                LevelChunk available = source.getChunkNow(pos.x, pos.z);
                if (available == null) continue;
                int count = LivingItem.processChunkContainers(
                    level, available, new IdentityHashMap<>(), new HashSet<>());
                if (count < 0) cache.removeChunk(Level.OVERWORLD, pos);
                else processed += count;
            }
            return processed;
        }
    }
}
