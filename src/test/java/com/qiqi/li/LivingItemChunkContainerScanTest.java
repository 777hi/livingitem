package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.assertAll;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingItemRegistrationScope;
import com.qiqi.li.living.container.ContainerChunkCache;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.brewing.BrewingStandSegment;
import com.qiqi.li.living.domain.brewing.BrewingStandSegment.BrewingRuntime;
import com.qiqi.li.living.domain.brewing.LivingBrewingStandData;
import com.qiqi.li.living.domain.brewing.LivingBrewingStandFunction;
import com.qiqi.li.living.domain.tnt.ExplosionData;
import com.qiqi.li.living.domain.tnt.ExplosionLedger;
import com.qiqi.li.living.domain.tnt.LivingTntData;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.FluidFlowBehavior;
import com.qiqi.li.living.domain.water.FluidFlowBehaviors;
import com.qiqi.li.living.domain.water.FluidFlowClientCache;
import com.qiqi.li.living.domain.water.FluidFlowServerSync;
import com.qiqi.li.living.domain.water.FluidFlowSyncPacket;
import com.qiqi.li.living.domain.water.WaterComponents;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** 区块容器扫描回归：战利品容器保留、真实附件重载后的发现与流体渲染恢复、重扫延后到 BE 权威（pending 窗口不误踢条目）。 */
@Isolated
class LivingItemChunkContainerScanTest {

    private static final ResourceKey<LootTable> LOOT = ResourceKey.create(
        Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/shipwreck_map"));
    private static final HolderLookup.Provider REGISTRIES = RegistryAccess.EMPTY;
    private static final BlockPos CHEST_POS = new BlockPos(1, 64, 2);
    private static final int SOURCE_SLOT = 13;
    private static final int BREWING_STAND_SLOT = 13;

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

    @Test
    @DisplayName("重扫延后到 BE 权威：可见但未 ticking 不误踢缓存条目；升 ticking 后补发现并恢复 tick")
    void rescan_defersVerdictUntilChunkTicking() {
        ChestWorld world = new ChestWorld();
        seedAndTick(world, Fluids.WATER.getFluidType());
        CompoundTag saved = saveChest(world.chest);

        // 首次发现：区块可处理 ⇒ Load → 重扫进缓存并处理（走远前的正常状态）
        ContainerChunkCache cache = ContainerChunkCache.getInstance();
        world.makeChunkAvailable();
        cache.onChunkLoad(new ChunkEvent.Load(world.chunk, true));
        assertEquals(1, world.tickDiscoveredChunks(), "可处理区块当拍发现并处理");
        assertEquals(1, cache.getCacheSize(Level.OVERWORLD));

        // 走远重载：卸载不清缓存条目（< 5min，stale 残留）；sweep 清容器级内存态；新 BE 随附件读回。
        world.demoteChunk();
        ContainerLivingItemHandler.clearAllCaches();
        world.reloadChest(saved);
        assertNull(ContainerLivingItemHandler.peekContainerDataByPos(
            world.level, CHEST_POS, ContainerFluidData.KEY), "重载后容器级内存态必须为空");

        // pending 窗口：区块重新可见（FULL）但未 ticking —— 真实区块此刻 BE 仍在
        // pendingBlockEntities（夹具以空 blockEntities 模拟）。重扫必须不判定：
        // 既不补发现，也不把 stale 条目踢掉，请求保留到升 ticking。
        world.loaded.put(world.chunkPos.toLong(), world.chunk);
        world.blockEntities.clear();
        Mockito.clearInvocations(world.level, world.source, world.connection);
        cache.onChunkLoad(new ChunkEvent.Load(world.chunk, true));
        assertEquals(0, world.tickDiscoveredChunks(), "BE 未权威拍：不扫描、不处理");
        assertEquals(1, cache.getCacheSize(Level.OVERWORLD),
            "扫描判据未权威 ⇒ 不得把「无容器」误判写进缓存（旧实现会在这里踢掉条目）");

        // 升 ticking：postProcessGeneration 已跑 ⇒ BE 晋升（blockEntities 有箱子）⇒ 补发现 + 恢复 tick
        world.blockEntities.put(CHEST_POS, world.chest);
        world.makeChunkAvailable();
        assertEquals(1, world.tickDiscoveredChunks(),
            "升 ticking 后由保留的重扫请求补发现 —— 没有第二次 Load 事件");
        assertEquals(1, cache.getCacheSize(Level.OVERWORLD));
        ContainerFluidData restored = ContainerLivingItemHandler.peekContainerDataByPos(
            world.level, CHEST_POS, ContainerFluidData.KEY);
        assertNotNull(restored, "恢复 tick 的首拍必须经附件回填建立流体运行时数据");
        assertEquals(Map.of(SOURCE_SLOT, Fluids.WATER.getFluidType()), restored.getGeneratedSources());
        assertTrue(restored.isSource(SOURCE_SLOT), "恢复后的源必须重新进入自维持驱动");
    }

    @Test
    @DisplayName("同拍拆除并原地重建箱子：旧酿造批次立即清除，新批次首拍扣燃料且保留到拍末")
    void rebuiltChest_startsFreshBrewingBatchOnFirstServerTick() throws ReflectiveOperationException {
        ChestWorld world = new ChestWorld();
        Mockito.when(world.server.getAllLevels()).thenReturn(List.of(world.level));
        Mockito.when(world.level.getAllEntities()).thenReturn(List.of());
        Mockito.when(world.players.getPlayers()).thenReturn(List.of());
        DimensionDataStorage storage = Mockito.mock(DimensionDataStorage.class);
        Mockito.when(world.level.getDataStorage()).thenReturn(storage);
        Mockito.when(storage.computeIfAbsent(Mockito.any(), Mockito.eq("living_item_pending_explosions")))
            .thenReturn(new ExplosionLedger());
        PotionBrewing.Builder recipes = new PotionBrewing.Builder(FeatureFlags.VANILLA_SET);
        recipes.addContainer(Items.POTION);
        recipes.addMix(Potions.WATER, Items.NETHER_WART, Potions.AWKWARD);
        Mockito.when(world.level.potionBrewing()).thenReturn(recipes.build());

        LivingItem mod = mockTickingMod();
        ContainerChunkCache cache = ContainerChunkCache.getInstance();
        ChestBlockEntity oldChest = world.chest;
        BlockState chestState = oldChest.getBlockState();
        var context = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, world.handler);
        assertNotNull(context);
        String key = context.getCacheKey();
        var freshBatch = new BrewingRuntime(400, Optional.of(Items.NETHER_WART));
        ContainerRuntimeCache.removeContainer(key);

        try (var functions = LivingItemRegistrationScope.isolated(List.of(new LivingBrewingStandFunction()))) {
            stockBrewingChest(oldChest);
            world.makeChunkAvailable();
            cache.onChunkLoad(new ChunkEvent.Load(world.chunk, true));
            world.serverTick(mod);
            assertEquals(freshBatch, brewingRuntime(key), "旧箱必须由区块 tick 开启 400 拍批次");

            cache.onBlockBreak(new BlockEvent.BreakEvent(world.level, CHEST_POS, chestState, world.viewer));
            world.blockEntities.remove(CHEST_POS);
            world.blockStates.remove(CHEST_POS);
            world.handler = null;
            boolean clearedOnBreak = ContainerRuntimeCache.getSnapshot(key).isEmpty();

            BlockSnapshot beforePlacement = BlockSnapshot.create(world.level.dimension(), world.level, CHEST_POS);
            ChestBlockEntity newChest = new ChestBlockEntity(CHEST_POS, chestState);
            world.installChest(newChest);
            stockBrewingChest(newChest);
            cache.onBlockPlace(new BlockEvent.EntityPlaceEvent(beforePlacement,
                world.level.getBlockState(CHEST_POS.below()), world.viewer));

            world.serverTick(mod);
            int firstTickFuel = LivingBrewingStandData.of(newChest.getItem(BREWING_STAND_SLOT)).fuel();
            BrewingRuntime firstTickBatch = brewingRuntime(key);
            world.serverTick(mod);
            BrewingRuntime secondTickBatch = brewingRuntime(key);
            int secondTickFuel = LivingBrewingStandData.of(newChest.getItem(BREWING_STAND_SLOT)).fuel();

            assertAll(
                () -> assertTrue(clearedOnBreak, "拆除后、重建前必须立即清空旧箱运行时数据"),
                () -> assertEquals(9, firstTickFuel, "新酿造台首拍必须扣一次燃料（10 → 9）"),
                () -> assertEquals(freshBatch, firstTickBatch, "新箱首拍结束时必须保有 400 拍新批次"),
                () -> assertEquals(new BrewingRuntime(399, freshBatch.batchIngredient()), secondTickBatch,
                    "下一拍必须继续新批次而非重启"),
                () -> assertEquals(9, secondTickFuel, "继续批次不再次扣燃料"));
        } finally {
            ContainerRuntimeCache.removeContainer(key);
        }
    }

    @Test
    @DisplayName("双箱 tick 中拆半：旧上下文不再扣酿造燃料，存活单箱下一拍开启新批次")
    void doubleChestSplitDuringTick_doesNotRestartBrewingInOldContext() {
        ChestWorld world = new ChestWorld();
        ChestBlockEntity right = world.installDoubleChest();
        ChestBlockEntity survivor = world.chest;
        BlockPos rightPos = right.getBlockPos();
        IItemHandler mergedHandler = world.handler;
        var doubleContext = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, mergedHandler);
        assertNotNull(doubleContext);
        assertEquals(List.of(CHEST_POS, rightPos), doubleContext.getAssociatedBlockPositions());
        String doubleKey = doubleContext.getCacheKey();
        int mergedStandSlot = right.getContainerSize() + BREWING_STAND_SLOT;
        var freshBatch = new BrewingRuntime(400, Optional.of(Items.NETHER_WART));

        PotionBrewing.Builder recipes = new PotionBrewing.Builder(FeatureFlags.VANILLA_SET);
        recipes.addContainer(Items.POTION);
        recipes.addMix(Potions.WATER, Items.NETHER_WART, Potions.AWKWARD);
        Mockito.when(world.level.potionBrewing()).thenReturn(recipes.build());

        LivingItemFunction splitChest = new LivingItemFunction() {
            @Override public boolean canApply(ItemStack stack) { return stack.is(Items.CRAFTING_TABLE); }
            @Override public String getFunctionId() { return "test_split_double_chest"; }

            @Override
            public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
                ContainerChunkCache.getInstance().onBlockBreak(
                    new BlockEvent.BreakEvent(world.level, rightPos, right.getBlockState(), world.viewer));
                world.blockEntities.remove(rightPos);
                world.blockStates.remove(rightPos);
                right.setRemoved();
                Mockito.when(world.level.getCapability(Capabilities.ItemHandler.BLOCK, rightPos, null))
                    .thenReturn(null);
                survivor.setBlockState(survivor.getBlockState().setValue(ChestBlock.TYPE, ChestType.SINGLE));
                world.installChest(survivor);
            }
        };

        ContainerRuntimeCache.removeContainer(doubleKey);
        try (var functions = LivingItemRegistrationScope.isolated(List.of(splitChest, new LivingBrewingStandFunction()))) {
            stockBrewingChest(survivor);
            world.gameTime.incrementAndGet();
            ContainerLivingItemHandler.processContext(doubleContext, world.level);
            assertEquals(freshBatch, ContainerRuntimeCache.get(doubleKey, mergedStandSlot)
                .get(BrewingStandSegment.INSTANCE), "拆半前双箱必须已开启真实酿造批次");
            assertEquals(9, LivingBrewingStandData.of(survivor.getItem(BREWING_STAND_SLOT)).fuel());

            // 原版合并槽序 RIGHT 在前；一次性设备先于酿造，并随被拆半箱离场。
            ItemStack trigger = new ItemStack(Items.CRAFTING_TABLE);
            LivingItemManager.setLiving(trigger, true);
            right.setItem(0, trigger);
            world.gameTime.incrementAndGet();
            // 拆半这拍只推进已解析的双箱，不能顺带推进新单箱。
            ContainerLivingItemHandler.processContext(doubleContext, world.level);
            assertNull(world.level.getBlockEntity(rightPos));
            ItemStack stand = survivor.getItem(BREWING_STAND_SLOT);
            assertSame(stand, mergedHandler.getStackInSlot(mergedStandSlot), "旧合并 handler 仍可读存活半箱的槽");
            int fuelAfterSplit = LivingBrewingStandData.of(stand).fuel();

            var singleContext = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, null);
            assertNotNull(singleContext);
            assertEquals(27, singleContext.getSize());
            String singleKey = singleContext.getCacheKey();
            assertFalse(doubleKey.equals(singleKey), "单箱必须使用独立于旧双箱的缓存键");
            try {
                world.gameTime.incrementAndGet();
                ContainerLivingItemHandler.processContext(singleContext, world.level);
                assertAll(
                    () -> assertEquals(9, fuelAfterSplit, "失效的双箱不能重新开批、多扣一次燃料"),
                    () -> assertEquals(freshBatch, brewingRuntime(singleKey), "合法单箱首拍开启 400 拍批次"),
                    () -> assertEquals(8, LivingBrewingStandData.of(survivor.getItem(BREWING_STAND_SLOT)).fuel(),
                        "只有合法单箱首拍扣一次燃料（9 → 8）"));
            } finally {
                ContainerRuntimeCache.removeContainer(singleKey);
            }
        } finally {
            ContainerRuntimeCache.removeContainer(doubleKey);
        }
    }

    private static LivingItem mockTickingMod() throws ReflectiveOperationException {
        LivingItem mod = Mockito.mock(LivingItem.class);
        // Mock 不执行模组构造器，只补齐真实 tick 所需的两张去重表。
        var handlerMap = LivingItem.class.getDeclaredField("reusableHandlerMap");
        handlerMap.setAccessible(true);
        handlerMap.set(mod, new IdentityHashMap<>());
        var keySet = LivingItem.class.getDeclaredField("reusableKeySet");
        keySet.setAccessible(true);
        keySet.set(mod, new HashSet<>());
        Mockito.doCallRealMethod().when(mod).onServerTick(Mockito.any(ServerTickEvent.Pre.class));
        return mod;
    }

    private static void stockBrewingChest(ChestBlockEntity chest) {
        ItemStack stand = new ItemStack(Items.BREWING_STAND);
        LivingItemManager.setLiving(stand, true);
        LivingBrewingStandData.set(stand, LivingBrewingStandData.DEFAULT.withFuel(10));
        chest.setItem(BREWING_STAND_SLOT, stand);
        chest.setItem(4, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        chest.setItem(14, new ItemStack(Items.NETHER_WART));
    }

    private static BrewingRuntime brewingRuntime(String key) {
        return ContainerRuntimeCache.get(key, BREWING_STAND_SLOT).get(BrewingStandSegment.INSTANCE);
    }

    @Test
    @DisplayName("整服同拍扫描：不同维度的同坐标箱子都实际推进流体")
    void samePositionInTwoDimensions_bothChestsTick() {
        ChestWorld overworld = new ChestWorld();
        ChestWorld nether = overworld.addDimension(Level.NETHER);
        FluidType water = Fluids.WATER.getFluidType();
        FluidType lava = Fluids.LAVA.getFluidType();
        ContainerFluidData waterData = seedFluid(overworld, water);
        ContainerFluidData lavaData = seedFluid(nether, lava);

        var processedHandlers = new IdentityHashMap<IItemHandler, Boolean>();
        Set<String> processedKeys = new HashSet<>();
        LivingItem.processChunkContainers(overworld.level, overworld.chunk, processedHandlers, processedKeys);
        LivingItem.processChunkContainers(nether.level, nether.chunk, processedHandlers, processedKeys);

        // 区块返回值只数查到能力的 BE；扩散格才证明两只箱子都进了实际 tick。
        assertFluidSpread(waterData, water);
        assertFluidSpread(lavaData, lava);
    }

    @Test
    @DisplayName("同维真双箱：两个不同 handler 只 tick 一次，左右半箱槽位都同步")
    void doubleChestWithDistinctHandlers_ticksOnceAndSyncsBothHalves() {
        ChestWorld world = new ChestWorld();
        ChestBlockEntity right = world.installDoubleChest();
        int slot = 0;
        int leftMenuSlot = right.getContainerSize() + slot;
        world.chest.setItem(slot, litTnt(80));
        right.setItem(slot, litTnt(40));

        world.processChest();

        ItemStack leftStack = world.chest.getItem(slot);
        ItemStack rightStack = right.getItem(slot);
        assertEquals(79, LivingTntData.of(leftStack).explosion().fuseTimer(), "左半箱引信只递减一拍");
        assertEquals(39, LivingTntData.of(rightStack).explosion().fuseTimer(), "右半箱引信只递减一拍");
        ArgumentCaptor<ClientboundContainerSetSlotPacket> packets =
            ArgumentCaptor.forClass(ClientboundContainerSetSlotPacket.class);
        Mockito.verify(world.connection, Mockito.times(2)).send(packets.capture());
        // 原版合并容器的物理槽是 RIGHT 在前；容器身份的位置排序是 LEFT 在前。
        assertSlotSync(world, packets.getAllValues(), slot, rightStack);
        assertSlotSync(world, packets.getAllValues(), leftMenuSlot, leftStack);
    }

    @Test
    @DisplayName("同一服玩家表：脏槽只发本维同坐标箱子菜单，不改异维菜单快照")
    void dirtyChestSlot_syncsOnlySameDimensionMenu() {
        ChestWorld overworld = new ChestWorld();
        ChestWorld nether = overworld.addDimension(Level.NETHER);
        ItemStack updated = new ItemStack(Items.DIAMOND, 3);
        ItemStack foreign = new ItemStack(Items.APPLE, 2);
        overworld.chest.setItem(SOURCE_SLOT, updated);
        nether.chest.setItem(SOURCE_SLOT, foreign);
        nether.viewer.containerMenu.setRemoteSlot(SOURCE_SLOT, foreign);

        var context = ContainerLivingItemHandler.resolveContextAt(overworld.level, CHEST_POS, overworld.handler);
        assertNotNull(context);
        context.setTickContext(new TickContext(context));
        try {
            context.syncSlotToClients(SOURCE_SLOT, updated);
            Mockito.verifyNoInteractions(overworld.connection, nether.connection);
            context.flushDirtySlots();
        } finally {
            context.setTickContext(null);
        }

        ArgumentCaptor<ClientboundContainerSetSlotPacket> packets =
            ArgumentCaptor.forClass(ClientboundContainerSetSlotPacket.class);
        Mockito.verify(overworld.connection).send(packets.capture());
        assertSlotSync(overworld, packets.getAllValues(), SOURCE_SLOT, updated);
        Mockito.verifyNoInteractions(nether.connection);
        assertTrue(ItemStack.matches(foreign, nether.viewer.containerMenu.remoteSlots.get(SOURCE_SLOT)),
            "异维同坐标菜单的远端快照必须保持异维槽位原值");
    }

    @Test
    @DisplayName("真实方块脏槽：同维同格的箱船菜单不发包，也不改远端快照")
    void dirtyChestSlot_doesNotSyncSamePositionEntityMenu() {
        ChestWorld world = new ChestWorld();
        ItemStack boatStack = new ItemStack(Items.APPLE, 2);
        ChestBoat boat = boatAtChest(world, boatStack);
        world.viewer.containerMenu = ChestMenu.threeRows(1, world.inventory, boat);
        world.viewer.containerMenu.setRemoteSlot(SOURCE_SLOT, boatStack.copy());

        flushChestSlot(world, new ItemStack(Items.DIAMOND, 3));

        assertAll(
            () -> Mockito.verifyNoInteractions(world.connection),
            () -> assertTrue(ItemStack.matches(boatStack, world.viewer.containerMenu.remoteSlots.get(SOURCE_SLOT)),
                "箱船的远端快照不得被同格箱子的脏槽覆盖"));
    }

    @Test
    @DisplayName("混合菜单：仅同步真实箱子槽，不覆盖同格箱船的同号槽")
    void dirtyChestSlot_syncsOnlyBlockSlotInMixedMenu() {
        ChestWorld world = new ChestWorld();
        ItemStack boatStack = new ItemStack(Items.APPLE, 2);
        ChestBoat boat = boatAtChest(world, boatStack);
        world.viewer.containerMenu = new MixedMenu(boat, world.chest);
        world.viewer.containerMenu.setRemoteSlot(0, boatStack.copy());
        ItemStack updated = new ItemStack(Items.DIAMOND, 3);

        flushChestSlot(world, updated);

        ArgumentCaptor<ClientboundContainerSetSlotPacket> packets =
            ArgumentCaptor.forClass(ClientboundContainerSetSlotPacket.class);
        assertAll(
            () -> {
                Mockito.verify(world.connection).send(packets.capture());
                assertSlotSync(world, packets.getAllValues(), 1, updated);
            },
            () -> assertTrue(ItemStack.matches(boatStack, world.viewer.containerMenu.remoteSlots.get(0)),
                "菜单里真实箱子槽的存在不能授权写箱船槽"));
    }

    private static ChestBoat boatAtChest(ChestWorld world, ItemStack stack) {
        ChestBoat boat = Mockito.mock(ChestBoat.class);
        Mockito.when(boat.getContainerSize()).thenReturn(27);
        Mockito.when(boat.level()).thenReturn(world.level);
        Mockito.when(boat.blockPosition()).thenReturn(CHEST_POS);
        Mockito.when(boat.getItem(SOURCE_SLOT)).thenReturn(stack);
        Mockito.when(boat.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(new InvWrapper(boat));
        return boat;
    }

    private static void flushChestSlot(ChestWorld world, ItemStack updated) {
        world.chest.setItem(SOURCE_SLOT, updated);
        var context = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, world.handler);
        assertNotNull(context);
        context.setTickContext(new TickContext(context));
        try {
            context.syncSlotToClients(SOURCE_SLOT, updated);
            Mockito.verifyNoInteractions(world.connection);
            context.flushDirtySlots();
        } finally {
            context.setTickContext(null);
        }
    }

    private static final class MixedMenu extends AbstractContainerMenu {
        MixedMenu(Container entity, Container block) {
            super(MenuType.GENERIC_9x3, 1);
            addSlot(new Slot(entity, SOURCE_SLOT, 0, 0));
            addSlot(new Slot(block, SOURCE_SLOT, 0, 0));
        }

        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    private static ContainerFluidData seedFluid(ChestWorld world, FluidType fluid) {
        var context = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, world.handler);
        assertNotNull(context);
        ContainerFluidData data = ContainerFluidData.getOrCreate(context);
        data.registerGeneratedSource(SOURCE_SLOT, fluid);
        return data;
    }

    private static void assertFluidSpread(ContainerFluidData data, FluidType fluid) {
        var flow = data.getFlows().get(SOURCE_SLOT + 1);
        assertNotNull(flow, "源格旁必须由实际 tick 生成流动格");
        assertEquals(SOURCE_SLOT, flow.fromSlot());
        assertSame(fluid, flow.fluid());
    }

    private static ItemStack litTnt(int fuseTicks) {
        ItemStack stack = new ItemStack(Items.TNT);
        LivingItemManager.setLiving(stack, true);
        LivingTntData.set(stack, new LivingTntData(ExplosionData.DEFAULT.ignite(fuseTicks)));
        return stack;
    }

    private static void assertSlotSync(ChestWorld world, List<ClientboundContainerSetSlotPacket> packets,
                                       int slot, ItemStack expected) {
        var menu = world.viewer.containerMenu;
        var packet = packets.stream().filter(p -> p.getSlot() == slot).findFirst().orElse(null);
        assertNotNull(packet, "菜单槽 " + slot + " 必须收到槽位包");
        assertEquals(menu.containerId, packet.getContainerId());
        assertTrue(ItemStack.matches(expected, packet.getItem()), "槽位包必须携带该槽最终内容");
        assertTrue(ItemStack.matches(expected, menu.remoteSlots.get(slot)), "菜单远端快照必须同步更新");
    }

    /** 倒入单源后经真实区块处理落附件；不直接调用流体引擎或写回钩子。 */
    private static ContainerFluidData seedAndTick(ChestWorld world, FluidType fluid) {
        var context = ContainerLivingItemHandler.resolveContextAt(world.level, CHEST_POS, world.handler);
        assertNotNull(context);
        assertEquals(27, context.getSize());
        assertEquals(9, context.getWidth());
        assertNull(context.getContainerUuid(), "原版箱子走 BE 附件，不走 UUID SavedData");
        ContainerFluidData data = ContainerFluidData.getOrCreate(context);
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

        world.makeChunkAvailable(); // 不再触发 ChunkEvent.Load
        assertEquals(1, world.tickDiscoveredChunks(),
            "后续可用且没有第二次 Load：maintain 必须重试发现，processChunkContainers 必须实际处理");
        assertTrue(cache.getProcessableChunks(world.level).contains(world.chunkPos));
        Mockito.verify(world.source, Mockito.never())
            .getChunk(Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }

    /** 世界、区块可用性与网络是边界替身；槽位和附件均由真实 ChestBlockEntity 持有。 */
    private static final class ChestWorld {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        final ChunkPos chunkPos = new ChunkPos(CHEST_POS);
        /** getChunkNow 可见性（区块已到 FULL）。 */
        final Map<Long, LevelChunk> loaded = new HashMap<>();
        /** isPositionTicking（BE 权威）：与 loaded 解耦，模拟「FULL 未 ticking」的 pending 窗口。 */
        final Set<Long> ticking = new HashSet<>();
        final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        final Map<BlockPos, BlockState> blockStates = new HashMap<>();
        final AtomicLong gameTime = new AtomicLong();
        final MinecraftServer server;
        final PlayerList players;
        final ServerPlayer viewer = Mockito.mock(ServerPlayer.class);
        final Inventory inventory = new Inventory(viewer);
        final ServerGamePacketListenerImpl connection = Mockito.mock(ServerGamePacketListenerImpl.class);
        ChestBlockEntity chest;
        IItemHandler handler;

        ChestWorld() {
            this(Level.OVERWORLD, Mockito.mock(MinecraftServer.class), Mockito.mock(PlayerList.class));
            Mockito.when(server.getPlayerList()).thenReturn(players);
            Mockito.when(players.getPlayers()).thenReturn(List.of(viewer));
        }

        private ChestWorld(ResourceKey<Level> dimension, MinecraftServer server, PlayerList players) {
            this.server = server;
            this.players = players;
            Mockito.when(level.dimension()).thenReturn(dimension);
            var dimensionType = Mockito.mock(net.minecraft.world.level.dimension.DimensionType.class);
            Mockito.when(level.dimensionType()).thenReturn(dimensionType);
            Mockito.when(level.getGameTime()).thenAnswer(inv -> gameTime.get());
            Mockito.when(level.getChunkSource()).thenReturn(source);
            Mockito.when(level.getBlockEntity(Mockito.any(BlockPos.class)))
                .thenAnswer(inv -> blockEntities.get(inv.getArgument(0)));
            Mockito.when(level.getBlockState(Mockito.any(BlockPos.class)))
                .thenAnswer(inv -> blockStates.getOrDefault(inv.getArgument(0), Blocks.AIR.defaultBlockState()));
            Mockito.when(level.getCapability(Capabilities.ItemHandler.BLOCK, CHEST_POS, null))
                .thenAnswer(inv -> handler);
            Mockito.when(source.getChunkNow(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(inv -> loaded.get(ChunkPos.asLong(
                    inv.getArgument(0, Integer.class), inv.getArgument(1, Integer.class))));
            Mockito.when(source.isPositionTicking(Mockito.anyLong()))
                .thenAnswer(inv -> ticking.contains(inv.getArgument(0, Long.class)));
            Mockito.when(chunk.getLevel()).thenReturn(level);
            Mockito.when(chunk.getPos()).thenReturn(chunkPos);
            Mockito.when(chunk.getBlockEntities()).thenReturn(blockEntities);

            Mockito.when(level.getServer()).thenReturn(server);
            Mockito.when(viewer.level()).thenReturn(level);
            Mockito.when(viewer.getInventory()).thenReturn(inventory);
            // 菜单仍绑定真实箱子槽位，但不触发开箱音效与开盖计数。
            Mockito.when(viewer.isSpectator()).thenReturn(true);
            viewer.connection = connection;
            installChest(new ChestBlockEntity(CHEST_POS, Blocks.CHEST.defaultBlockState()));
        }

        ChestWorld addDimension(ResourceKey<Level> dimension) {
            ChestWorld other = new ChestWorld(dimension, server, players);
            Mockito.when(players.getPlayers()).thenReturn(List.of(viewer, other.viewer));
            return other;
        }

        ChestBlockEntity installDoubleChest() {
            ChestBlock block = (ChestBlock) Blocks.CHEST;
            BlockState leftState = block.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockPos rightPos = CHEST_POS.relative(ChestBlock.getConnectedDirection(leftState));
            BlockState rightState = leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT);
            chest = new ChestBlockEntity(CHEST_POS, leftState);
            ChestBlockEntity right = new ChestBlockEntity(rightPos, rightState);
            placeChest(chest);
            placeChest(right);

            var leftContents = ChestBlock.getContainer(block, leftState, level, CHEST_POS, true);
            var rightContents = ChestBlock.getContainer(block, rightState, level, rightPos, true);
            assertNotNull(leftContents);
            assertNotNull(rightContents);
            handler = new InvWrapper(leftContents);
            IItemHandler rightHandler = new InvWrapper(rightContents);
            assertEquals(54, handler.getSlots());
            assertEquals(54, rightHandler.getSlots());
            Mockito.when(level.getCapability(Capabilities.ItemHandler.BLOCK, rightPos, null))
                .thenReturn(rightHandler);
            viewer.containerMenu = ChestMenu.sixRows(1, inventory, leftContents);
            return right;
        }

        void reloadChest(CompoundTag saved) {
            ChestBlockEntity reloaded = new ChestBlockEntity(CHEST_POS, Blocks.CHEST.defaultBlockState());
            reloaded.loadWithComponents(saved, REGISTRIES);
            installChest(reloaded);
        }

        /** 区块「可见且可处理」：loaded（getChunkNow 可见）+ ticking（BE 权威）同时登记。 */
        void makeChunkAvailable() {
            loaded.put(chunkPos.toLong(), chunk);
            ticking.add(chunkPos.toLong());
        }

        /** 模拟卸载 / 降级：区块不再可见、不再可处理；缓存条目是否保留由生产代码决定。 */
        void demoteChunk() {
            loaded.remove(chunkPos.toLong());
            ticking.remove(chunkPos.toLong());
        }

        private void installChest(ChestBlockEntity current) {
            chest = current;
            placeChest(chest);
            handler = new InvWrapper(chest);
            viewer.containerMenu = ChestMenu.threeRows(1, inventory, chest);
        }

        private void placeChest(ChestBlockEntity be) {
            be.setLevel(level);
            blockEntities.put(be.getBlockPos(), be);
            blockStates.put(be.getBlockPos(), be.getBlockState());
        }

        int processChest() {
            return LivingItem.processChunkContainers(level, chunk, new IdentityHashMap<>(), new HashSet<>());
        }

        void serverTick(LivingItem mod) {
            gameTime.incrementAndGet();
            mod.onServerTick(new ServerTickEvent.Pre(() -> true, server));
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
