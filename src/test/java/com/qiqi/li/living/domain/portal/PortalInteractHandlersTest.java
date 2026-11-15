package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.FluidPresence;
import com.qiqi.li.living.api.FluidSourceLedger;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.interaction.InteractionAction;
import com.qiqi.li.living.interaction.MenuActionAccess;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** 真实门交互入口、整区授权、光标耐久与槽内货物守卫；世界传送和网络发送隔离。 */
class PortalInteractHandlersTest {
    private static final int WIDTH = 5;
    private static final int TARGET = 6;
    private static final int OTHER_CELL = 7;
    private static final int UNRELATED = 18;
    private static final Set<Integer> REGION = Set.of(6, 7, 8);

    private final List<MockedStatic<?>> staticMocks = new ArrayList<>();
    private final List<MapState> cacheStates = new ArrayList<>();
    private final AtomicInteger fluidQueries = new AtomicInteger();
    private List<MenuActionAccess.Resolver> resolvers;
    private List<MenuActionAccess.Resolver> previousResolvers;
    private Function<TickContext, FluidPresence> previousPresence;
    private Function<TickContext, FluidSourceLedger> previousLedger;
    private boolean fluidResolversSaved;
    private ContainerDataSavedData saved;
    private ContainerFluidData fluids;
    private Context ctx;
    private ContainerContext resolvedContext;
    private TestMenu menu;
    private ServerPlayer player;
    private AccessResolver access;
    private MockedStatic<PortalServerSync> sync;
    private MockedStatic<PortalPlayerTeleport> teleport;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        for (String name : List.of("CONTAINER_DATA", "POS_TO_CACHE_KEY", "LAST_SEEN")) {
            Map<Object, Object> cache = (Map<Object, Object>) field(ContainerLivingItemHandler.class, name).get(null);
            cacheStates.add(new MapState(cache, new HashMap<>(cache)));
            cache.clear();
        }
        resolvers = (List<MenuActionAccess.Resolver>) field(MenuActionAccess.class, "RESOLVERS").get(null);
        previousResolvers = new ArrayList<>(resolvers);
        resolvers.clear();
        previousPresence = (Function<TickContext, FluidPresence>) field(TickContext.class, "fluidPresenceResolver").get(null);
        previousLedger = (Function<TickContext, FluidSourceLedger>) field(TickContext.class, "sourceLedgerResolver").get(null);
        fluidResolversSaved = true;
        fluids = new ContainerFluidData();
        TickContext.registerFluidPresenceResolver(tick -> {
            fluidQueries.incrementAndGet();
            return fluids;
        });
        TickContext.registerSourceLedgerResolver(tick -> {
            fluidQueries.incrementAndGet();
            return fluids;
        });

        // get 仅替换存档实例；读写、编解码、dirty 标记均使用真实 SavedData。
        saved = ContainerDataSavedData.load(new CompoundTag(), null);
        MockedStatic<ContainerDataSavedData> savedAccess = mockStatic(ContainerDataSavedData.class, CALLS_REAL_METHODS);
        staticMocks.add(savedAccess);
        savedAccess.when(ContainerDataSavedData::get).thenReturn(saved);
        player = mock(ServerPlayer.class);
        when(player.getRandom()).thenReturn(RandomSource.create(1234L));
        when(player.getAdvancements()).thenReturn(mock(PlayerAdvancements.class));
        fixture(0);

        MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class);
        staticMocks.add(contexts);
        contexts.when(() -> ContainerContexts.resolve(eq(player), any(Slot.class)))
            .thenAnswer(invocation -> menu.slots.contains(invocation.getArgument(1)) ? resolvedContext : null);
        sync = mockStatic(PortalServerSync.class);
        staticMocks.add(sync);
        teleport = mockStatic(PortalPlayerTeleport.class);
        staticMocks.add(teleport);
        access = new AccessResolver();
        MenuActionAccess.register(access);
    }

    @AfterEach
    void tearDown() {
        try {
            for (int i = staticMocks.size() - 1; i >= 0; i--) staticMocks.get(i).close();
        } finally {
            if (fluidResolversSaved) {
                TickContext.registerFluidPresenceResolver(previousPresence);
                TickContext.registerSourceLedgerResolver(previousLedger);
            }
            if (previousResolvers != null) {
                resolvers.clear();
                resolvers.addAll(previousResolvers);
            }
            for (MapState state : cacheStates) {
                state.cache().clear();
                state.cache().putAll(state.before());
            }
        }
    }

    private void fixture(int physicalOffset) {
        ctx = new Context(physicalOffset);
        resolvedContext = ctx;
        menu = new TestMenu(ctx.storage);
        player.containerMenu = menu;
        when(player.level()).thenReturn(ctx.level);
        when(player.serverLevel()).thenReturn(ctx.level);
        when(player.hasInfiniteMaterials()).thenReturn(false);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void creativeIgnitesEmptyRealHandlerSlotAndPersistsLogicalRegion(int physicalOffset) {
        fixture(physicalOffset);
        when(player.hasInfiniteMaterials()).thenReturn(true);
        ItemStack igniter = igniter();
        menu.setCarried(igniter);
        ItemStack cargo = cargo();
        ctx.setItem(UNRELATED, cargo);
        List<ItemStack> inventory = inventorySnapshot();
        Slot target = target(TARGET);
        assertTrue(target instanceof SlotItemHandler);
        assertTrue(target.getItem().isEmpty());
        assertFalse(target.mayPickup(player));
        assertEquals(TARGET + physicalOffset, target.getContainerSlot());

        PortalInteractHandlers.IGNITE.handle(player, target);

        assertDoorPersisted();
        assertRequest(InteractionAction.Kind.CONTAINER_STATE, target);
        assertSame(igniter, menu.getCarried());
        assertEquals(7, menu.getCarried().getDamageValue());
        assertSame(cargo, ctx.getItem(UNRELATED));
        assertInventoryUnchanged(inventory);
        sync.verify(() -> PortalServerSync.flush(eq(ctx), any(TickContext.class)), times(1));
        teleport.verifyNoInteractions();
    }

    @Test
    void survivalIgnitionDamagesRealCarriedCopyOnceAndRepeatedIgnitionDoesNotCharge() {
        ItemStack igniter = igniter();
        assertTrue(igniter.isDamageableItem());
        menu.setCarried(igniter);
        ctx.setItem(UNRELATED, cargo());
        List<ItemStack> inventory = inventorySnapshot();

        PortalInteractHandlers.IGNITE.handle(player, target(TARGET));

        assertDoorPersisted();
        ItemStack damaged = menu.getCarried();
        assertNotSame(igniter, damaged);
        assertTrue(damaged.is(Items.FLINT_AND_STEEL));
        assertTrue(LivingItemManager.isLivingItem(damaged));
        assertEquals(1, damaged.getCount());
        assertEquals(8, damaged.getDamageValue());
        assertEquals(7, igniter.getDamageValue(), "损耗只提交到新的光标栈");
        CompoundTag firstSave = savedSnapshot();
        saved.setDirty(false);

        PortalInteractHandlers.IGNITE.handle(player, target(TARGET));

        assertSame(damaged, menu.getCarried());
        assertEquals(8, damaged.getDamageValue());
        assertEquals(firstSave, savedSnapshot());
        assertFalse(saved.isDirty());
        assertEquals(2, access.requests.size());
        assertInventoryUnchanged(inventory);
        sync.verify(() -> PortalServerSync.flush(eq(ctx), any(TickContext.class)), times(1));
        teleport.verifyNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = MenuActionAccess.Decision.class, names = {"DENY", "UNKNOWN"})
    void deniedOrUnknownIgnitionDoesNotMaintainPersistDamageOrTouchCargo(MenuActionAccess.Decision decision) {
        // 失效旧门若进入维护必被删除；拒绝时必须原样保存。
        saved.put(ctx.uuid, ContainerPortalData.KEY, ContainerPortalData.EMPTY.withRegion(WIDTH, Set.of(UNRELATED)));
        saved.setDirty(false);
        access.defaultDecision = decision;
        ItemStack igniter = igniter();
        menu.setCarried(igniter);
        ctx.setItem(UNRELATED, cargo());
        CompoundTag before = savedSnapshot();
        List<ItemStack> inventory = inventorySnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();

        PortalInteractHandlers.IGNITE.handle(player, target(TARGET));

        assertRequest(InteractionAction.Kind.CONTAINER_STATE, target(TARGET));
        assertRejectedWithoutMutation(before, hotBefore, inventory, igniter);
    }

    @ParameterizedTest
    @EnumSource(value = MenuActionAccess.Decision.class, names = {"DENY", "UNKNOWN"})
    void deniedOrUnknownUseReadsSavedCandidateWithoutMaintainingIt(MenuActionAccess.Decision decision) {
        saveDoorCold();
        ctx.setItem(OTHER_CELL, living(Items.STONE));
        ctx.setItem(8, cargo());
        access.defaultDecision = decision;
        assertEquals(REGION, PortalDataAccess.peekSaved(ctx).portalSlots());
        assertTrue(hotSnapshot().isEmpty());
        CompoundTag before = savedSnapshot();
        List<ItemStack> inventory = inventorySnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();

        PortalInteractHandlers.USE.handle(player, target(TARGET));

        assertRequest(InteractionAction.Kind.USE_CONTAINER, target(TARGET));
        assertRejectedWithoutMutation(before, hotBefore, inventory, ItemStack.EMPTY);
    }

    @ParameterizedTest
    @EnumSource(value = InteractionAction.Kind.class, names = {"CONTAINER_STATE", "USE_CONTAINER"})
    void otherRegionCellPermissionBlocksButUnrelatedSlotPermissionDoesNot(InteractionAction.Kind kind) {
        boolean ignition = kind == InteractionAction.Kind.CONTAINER_STATE;
        when(player.hasInfiniteMaterials()).thenReturn(true);
        if (ignition) menu.setCarried(igniter());
        else saveDoorCold();
        ctx.setItem(UNRELATED, cargo());
        access.slotDecisions.put(OTHER_CELL, MenuActionAccess.Decision.DENY);
        CompoundTag before = savedSnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();
        List<ItemStack> inventory = inventorySnapshot();
        ItemStack carried = menu.getCarried();

        handle(kind, target(TARGET));

        assertRequest(kind, target(TARGET));
        assertRejectedWithoutMutation(before, hotBefore, inventory, carried);
        access.requests.clear();
        access.slotDecisions.clear();
        access.slotDecisions.put(UNRELATED, MenuActionAccess.Decision.DENY);

        handle(kind, target(TARGET));

        assertRequest(kind, target(TARGET));
        assertDoorPersisted();
        assertSame(carried, menu.getCarried());
        assertInventoryUnchanged(inventory);
        if (ignition) {
            sync.verify(() -> PortalServerSync.flush(eq(ctx), any(TickContext.class)), times(1));
            teleport.verifyNoInteractions();
        } else {
            verifyTeleportOnce();
            sync.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {TARGET, OTHER_CELL})
    void authorizedUseOfEmptyOrCargoCellTeleportsAndPreservesOrdinaryCargo(int logicalTarget) {
        saveDoorCold();
        ItemStack cargo = cargo();
        ctx.setItem(OTHER_CELL, cargo);
        List<ItemStack> inventory = inventorySnapshot();
        Slot target = target(logicalTarget);
        if (logicalTarget == TARGET) {
            assertTrue(target.getItem().isEmpty());
            assertFalse(target.mayPickup(player), "空 handler 槽不可抽取不妨碍已授权的使用");
        } else {
            assertSame(cargo, target.getItem());
        }
        assertTrue(hotSnapshot().isEmpty());
        CompoundTag before = savedSnapshot();

        PortalInteractHandlers.USE.handle(player, target);

        assertRequest(InteractionAction.Kind.USE_CONTAINER, target);
        verifyTeleportOnce();
        assertDoorPersisted();
        assertEquals(before, savedSnapshot());
        assertFalse(saved.isDirty(), "完整门维护无需重写存档");
        assertSame(cargo, ctx.getItem(OTHER_CELL));
        assertEquals(37, cargo.getCount());
        assertEquals(Component.literal("portal cargo"), cargo.get(DataComponents.CUSTOM_NAME));
        assertInventoryUnchanged(inventory);
        assertTrue(menu.getCarried().isEmpty());
        sync.verifyNoInteractions();
        verify(ctx.level, never()).addFreshEntity(any());
    }

    @Test
    void authorizedUseMaintainsInvalidDoorWithoutTeleportingOrConsumingItsItems() {
        saveDoorCold();
        ItemStack blocker = living(Items.STONE);
        ItemStack cargo = cargo();
        ctx.setItem(OTHER_CELL, blocker);
        ctx.setItem(8, cargo);
        List<ItemStack> inventory = inventorySnapshot();

        PortalInteractHandlers.USE.handle(player, target(TARGET));

        assertRequest(InteractionAction.Kind.USE_CONTAINER, target(TARGET));
        assertNull(saved.get(ctx.uuid, ContainerPortalData.KEY));
        assertNull(ctx.peekContainerData(ContainerPortalData.KEY));
        assertTrue(saved.isDirty());
        assertSame(blocker, ctx.getItem(OTHER_CELL));
        assertSame(cargo, ctx.getItem(8));
        assertInventoryUnchanged(inventory);
        sync.verify(() -> PortalServerSync.flush(eq(ctx), any(TickContext.class)), times(1));
        teleport.verifyNoInteractions();
    }

    @Test
    void useWithoutSavedDoorDoesNotCreateHotPortalDataOrQueryPermission() {
        CompoundTag before = savedSnapshot();
        List<ItemStack> inventory = inventorySnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();
        assertSame(ContainerPortalData.EMPTY, PortalDataAccess.peekSaved(ctx));

        PortalInteractHandlers.USE.handle(player, target(TARGET));

        assertTrue(access.requests.isEmpty());
        assertRejectedWithoutMutation(before, hotBefore, inventory, ItemStack.EMPTY);
        assertNull(ctx.peekContainerData(ContainerPortalData.KEY));
    }

    @Test
    void unclaimedRealHandlerSlotRemainsUnknownAndCannotIgnite() {
        resolvers.clear();
        ItemStack igniter = igniter();
        menu.setCarried(igniter);
        CompoundTag before = savedSnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();
        List<ItemStack> inventory = inventorySnapshot();
        assertFalse(target(TARGET).mayPickup(player));

        PortalInteractHandlers.IGNITE.handle(player, target(TARGET));

        assertTrue(access.requests.isEmpty());
        assertRejectedWithoutMutation(before, hotBefore, inventory, igniter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cargo", "actual_fluid", "immediate_source"})
    void authorizedIgnitionRechecksEveryCellAndChargesOnlyForSuccess(String obstruction) {
        ItemStack igniter = igniter();
        menu.setCarried(igniter);
        if (obstruction.equals("cargo")) ctx.setItem(OTHER_CELL, cargo());
        else if (obstruction.equals("actual_fluid")) {
            fluids.getFlows().put(OTHER_CELL,
                new ContainerFluidData.FlowEntry(4, false, 1, Fluids.WATER.getFluidType()));
        } else fluids.registerGeneratedSource(OTHER_CELL, Fluids.WATER.getFluidType());
        List<ItemStack> inventory = inventorySnapshot();

        PortalInteractHandlers.IGNITE.handle(player, target(TARGET));

        assertRequest(InteractionAction.Kind.CONTAINER_STATE, target(TARGET));
        assertNull(saved.get(ctx.uuid, ContainerPortalData.KEY));
        assertFalse(saved.isDirty());
        assertTrue(ctx.peekContainerData(ContainerPortalData.KEY).isEmpty());
        assertSame(igniter, menu.getCarried());
        assertEquals(7, igniter.getDamageValue());
        assertInventoryUnchanged(inventory);
        if (obstruction.equals("actual_fluid")) assertTrue(fluids.hasFluidAt(OTHER_CELL));
        if (obstruction.equals("immediate_source")) assertNotNull(fluids.sourceAt(OTHER_CELL));
        sync.verifyNoInteractions();
        teleport.verifyNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_menu", "detached_slot"})
    void invalidMenuOrNonidenticalSlotRejectsBothPublicHandlersBeforeAuthorization(String condition) {
        saveDoorCold();
        Slot target = target(TARGET);
        if (condition.equals("invalid_menu")) menu.valid = false;
        else {
            Slot detached = new SlotItemHandler(ctx.storage, ctx.toPhysicalSlot(TARGET), 0, 0);
            detached.index = target.index;
            target = detached;
        }
        CompoundTag before = savedSnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();
        List<ItemStack> inventory = inventorySnapshot();
        ItemStack igniter = igniter();
        menu.setCarried(igniter);

        PortalInteractHandlers.IGNITE.handle(player, target);

        assertRejectedWithoutMutation(before, hotBefore, inventory, igniter);
        menu.setCarried(ItemStack.EMPTY);
        PortalInteractHandlers.USE.handle(player, target);
        assertRejectedWithoutMutation(before, hotBefore, inventory, ItemStack.EMPTY);
        assertTrue(access.requests.isEmpty());
    }

    @Test
    void ordinaryIgniterAndNonemptyUseCursorRejectBeforeAuthorization() {
        saveDoorCold();
        ItemStack ordinaryFlint = new ItemStack(Items.FLINT_AND_STEEL);
        ordinaryFlint.setDamageValue(7);
        menu.setCarried(ordinaryFlint);
        CompoundTag before = savedSnapshot();
        Map<Object, Object> hotBefore = hotSnapshot();
        List<ItemStack> inventory = inventorySnapshot();

        PortalInteractHandlers.IGNITE.handle(player, target(TARGET));
        assertRejectedWithoutMutation(before, hotBefore, inventory, ordinaryFlint);
        ItemStack nonempty = cargo();
        menu.setCarried(nonempty);
        PortalInteractHandlers.USE.handle(player, target(TARGET));
        assertRejectedWithoutMutation(before, hotBefore, inventory, nonempty);
        assertTrue(access.requests.isEmpty());
    }

    @Test
    void realSimpleContextUseWithoutSavedDoorDoesNotCreateCachesOrUuidAttachment() {
        BlockPos pos = new BlockPos(214, 64, 218);
        ChestBlockEntity host = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        host.setLevel(ctx.level);
        SimpleContainerContext real = new SimpleContainerContext(ctx.storage, null,
            List.of(pos), List.of(host), ctx.level);
        resolvedContext = real;
        assertFalse(host.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(host.hasData(PortalComponents.CONTAINER_PORTAL_DATA));
        Map<Object, Object> hotBefore = hotSnapshot();
        List<Map<Object, Object>> cachesBefore = cacheSnapshots();
        CompoundTag before = savedSnapshot();
        List<ItemStack> inventory = inventorySnapshot();

        assertSame(ContainerPortalData.EMPTY, PortalDataAccess.peekSaved(real));
        PortalInteractHandlers.USE.handle(player, target(TARGET));

        assertTrue(access.requests.isEmpty());
        assertRejectedWithoutMutation(before, hotBefore, inventory, ItemStack.EMPTY);
        assertCachesUnchanged(cachesBefore);
        assertFalse(host.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(host.hasData(PortalComponents.CONTAINER_PORTAL_DATA));
        assertNull(real.getContainerUuid());
    }

    @ParameterizedTest
    @EnumSource(value = MenuActionAccess.Decision.class, names = {"DENY", "UNKNOWN"})
    void realSimpleContextDeniedUseKeepsColdAttachmentCandidateAndAllIndices(MenuActionAccess.Decision decision) {
        BlockPos firstPos = new BlockPos(224, 64, 228);
        ChestBlockEntity first = new ChestBlockEntity(firstPos, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity second = new ChestBlockEntity(firstPos.east(), Blocks.CHEST.defaultBlockState());
        ItemStackHandler storage = new ItemStackHandler(54);
        SimpleContainerContext real = new SimpleContainerContext(storage, null,
            List.of(firstPos, firstPos.east()), List.of(first, second), ctx.level);
        ContainerPortalData candidate = ContainerPortalData.EMPTY.withRegion(real.getWidth(), Set.of(10));
        first.setData(PortalComponents.CONTAINER_PORTAL_DATA.get(), ContainerPortalData.EMPTY);
        second.setData(PortalComponents.CONTAINER_PORTAL_DATA.get(), candidate);
        first.setLevel(ctx.level);
        second.setLevel(ctx.level);
        menu = new TestMenu(storage);
        player.containerMenu = menu;
        resolvedContext = real;
        access.defaultDecision = decision;
        Slot clicked = menu.getSlot(10);
        assertTrue(clicked.getItem().isEmpty());
        assertFalse(clicked.mayPickup(player));
        List<Map<Object, Object>> before = cacheSnapshots();
        CompoundTag savedBefore = savedSnapshot();

        assertEquals(candidate, PortalDataAccess.peekSaved(real));
        PortalInteractHandlers.USE.handle(player, clicked);

        assertEquals(1, access.requests.size());
        MenuActionAccess.Request request = access.requests.get(0);
        assertSame(real, request.context());
        assertSame(clicked, request.target());
        assertEquals(InteractionAction.Kind.USE_CONTAINER, request.kind());
        assertEquals(Set.of(10), request.logicalSlots());
        assertCachesUnchanged(before);
        assertEquals(0, fluidQueries.get());
        assertEquals(savedBefore, savedSnapshot());
        assertFalse(saved.isDirty());
        assertFalse(first.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(second.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertEquals(ContainerPortalData.EMPTY, first.getExistingDataOrNull(PortalComponents.CONTAINER_PORTAL_DATA.get()));
        assertEquals(candidate, second.getExistingDataOrNull(PortalComponents.CONTAINER_PORTAL_DATA.get()));
        for (int physical = 0; physical < storage.getSlots(); physical++) {
            assertTrue(storage.getStackInSlot(physical).isEmpty());
        }
        assertTrue(menu.getCarried().isEmpty());
        sync.verifyNoInteractions();
        teleport.verifyNoInteractions();
    }

    private List<Map<Object, Object>> cacheSnapshots() {
        List<Map<Object, Object>> snapshots = new ArrayList<>();
        for (MapState state : cacheStates) snapshots.add(new HashMap<>(state.cache()));
        return snapshots;
    }

    private void assertCachesUnchanged(List<Map<Object, Object>> before) {
        for (int i = 0; i < before.size(); i++) assertEquals(before.get(i), cacheStates.get(i).cache());
    }

    private void handle(InteractionAction.Kind kind, Slot target) {
        if (kind == InteractionAction.Kind.CONTAINER_STATE) PortalInteractHandlers.IGNITE.handle(player, target);
        else PortalInteractHandlers.USE.handle(player, target);
    }

    private void saveDoorCold() {
        saved.put(ctx.uuid, ContainerPortalData.KEY, door());
        saved.setDirty(false);
        assertTrue(hotSnapshot().isEmpty());
    }

    private static ContainerPortalData door() {
        return ContainerPortalData.EMPTY.withRegion(WIDTH, REGION);
    }

    private void assertDoorPersisted() {
        assertEquals(door(), saved.get(ctx.uuid, ContainerPortalData.KEY));
        assertEquals(door(), ctx.peekContainerData(ContainerPortalData.KEY));
        ContainerDataSavedData reloaded = ContainerDataSavedData.load(savedSnapshot(), null);
        assertEquals(door(), reloaded.get(ctx.uuid, ContainerPortalData.KEY));
    }

    private void assertRequest(InteractionAction.Kind kind, Slot target) {
        assertFalse(access.requests.isEmpty());
        for (MenuActionAccess.Request request : access.requests) {
            assertSame(player, request.player());
            assertSame(menu, request.menu());
            assertSame(target, request.target());
            assertSame(ctx, request.context());
            assertEquals(kind, request.kind());
            assertEquals(REGION, request.logicalSlots());
            assertFalse(request.logicalSlots().contains(UNRELATED));
        }
    }

    private void assertRejectedWithoutMutation(CompoundTag before, Map<Object, Object> hotBefore,
                                               List<ItemStack> inventory, ItemStack carried) {
        assertEquals(before, savedSnapshot());
        assertFalse(saved.isDirty());
        assertEquals(hotBefore, hotSnapshot(), "授权失败不建立或回填热门数据");
        assertEquals(0, fluidQueries.get(), "授权失败不进入门维护和流体查询");
        assertSame(carried, menu.getCarried());
        if (carried.is(Items.FLINT_AND_STEEL)) assertEquals(7, carried.getDamageValue());
        assertInventoryUnchanged(inventory);
        sync.verifyNoInteractions();
        teleport.verifyNoInteractions();
    }

    private void verifyTeleportOnce() {
        teleport.verify(() -> PortalPlayerTeleport.teleport(player,
            new PortalPositionResolver.Origin(ctx.level, Vec3.atCenterOf(ctx.getBlockPos()))), times(1));
        teleport.verifyNoMoreInteractions();
    }

    private CompoundTag savedSnapshot() {
        return saved.save(new CompoundTag(), null).copy();
    }

    private Map<Object, Object> hotSnapshot() {
        return new HashMap<>(cacheStates.get(0).cache());
    }

    private List<ItemStack> inventorySnapshot() {
        List<ItemStack> snapshot = new ArrayList<>();
        for (int i = 0; i < ctx.storage.getSlots(); i++) snapshot.add(ctx.storage.getStackInSlot(i).copy());
        return snapshot;
    }

    private void assertInventoryUnchanged(List<ItemStack> before) {
        for (int i = 0; i < before.size(); i++) {
            assertTrue(ItemStack.matches(before.get(i), ctx.storage.getStackInSlot(i)), "物理槽 " + i + " 的数量或组件被修改");
        }
    }

    private Slot target(int logical) {
        return menu.getSlot(ctx.toPhysicalSlot(logical));
    }

    private static ItemStack living(Item item) {
        ItemStack stack = new ItemStack(item);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack igniter() {
        ItemStack stack = living(Items.FLINT_AND_STEEL);
        stack.setDamageValue(7);
        return stack;
    }

    private static ItemStack cargo() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 37);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("portal cargo"));
        return stack;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record MapState(Map<Object, Object> cache, Map<Object, Object> before) {}

    /** 门网格与真实菜单共享同一 handler；自身方块锚点与 UUID 归属相互独立。 */
    private static final class Context extends FakeContainerContext implements TickableContainerContext {
        final UUID uuid = UUID.randomUUID();
        final ServerLevel level = mock(ServerLevel.class);
        final ItemStackHandler storage;
        final int physicalOffset;

        Context(int physicalOffset) {
            super(25, WIDTH, "portal_interact_test_" + UUID.randomUUID());
            this.physicalOffset = physicalOffset;
            storage = new ItemStackHandler(25 + physicalOffset);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            withBlockPos(new BlockPos(4, 64, 8));
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < WIDTH; col++) {
                    if (row == 0 || row == 2 || col == 0 || col == WIDTH - 1) {
                        setItem(row * WIDTH + col, living(Items.OBSIDIAN));
                    }
                }
            }
        }

        @Override public Level getLevel() { return level; }
        @Override public UUID getContainerUuid() { return uuid; }
        @Override public int toPhysicalSlot(int logical) { return logical + physicalOffset; }
        @Override public int toLogicalSlot(int physical) { return physical - physicalOffset; }
        @Override public ItemStack getItem(int logical) {
            return isValidSlot(logical) ? storage.getStackInSlot(toPhysicalSlot(logical)) : ItemStack.EMPTY;
        }
        @Override public void setItem(int logical, ItemStack stack) {
            storage.setStackInSlot(toPhysicalSlot(logical), stack);
        }
        @Override public <T> T peekContainerData(ContainerDataKey<T> key) {
            return ContainerLivingItemHandler.peekContainerDataByPos(level, getBlockPos(), key);
        }
        @Override public void setTickContext(TickContext tick) {}
        @Override public void flushDirtySlots() {}
        @Override public List<BlockEntity> getAssociatedBlockEntities() { return List.of(); }
        @Override public Inventory getInventory() { return null; }
    }

    /** 真实 SlotItemHandler 列表；仍用原版光标存储，不模拟抽取授权。 */
    private static final class TestMenu extends AbstractContainerMenu {
        boolean valid = true;

        TestMenu(ItemStackHandler storage) {
            super(null, 7);
            for (int physical = 0; physical < storage.getSlots(); physical++) {
                addSlot(new SlotItemHandler(storage, physical, 0, 0));
            }
        }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return valid; }
    }

    /** 我方权限接口夹具；仅按 request 中的逻辑范围回答，不猜第三方菜单行为。 */
    private final class AccessResolver implements MenuActionAccess.Resolver {
        final List<MenuActionAccess.Request> requests = new ArrayList<>();
        final Map<Integer, MenuActionAccess.Decision> slotDecisions = new HashMap<>();
        MenuActionAccess.Decision defaultDecision = MenuActionAccess.Decision.ALLOW;

        @Override public boolean supports(AbstractContainerMenu candidate) { return candidate == menu; }
        @Override public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
            requests.add(request);
            for (int logical : request.logicalSlots()) {
                MenuActionAccess.Decision decision = slotDecisions.get(logical);
                if (decision != null && decision != MenuActionAccess.Decision.ALLOW) return decision;
            }
            return defaultDecision;
        }
    }
}
