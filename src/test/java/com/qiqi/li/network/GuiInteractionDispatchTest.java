package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.FluidSourceLedger;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.container.ContainerDataStore;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.domain.portal.PortalInteractHandlers;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.LivingBucketInteractHandlers;
import com.qiqi.li.living.interaction.InteractionAction;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.MenuActionAccess;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/** 真实服务端分发与桶源账本；世界门面维护在静态边界隔离。 */
class GuiInteractionDispatchTest {
    private static final String ACTION = "test_dispatch_item";
    private static final String POUR = "living_bucket_pour";
    private static final String SCOOP = "living_bucket_scoop";
    private static final int PHYSICAL = 4;
    private static final int ALIAS_PHYSICAL = 5;

    private final Map<String, InteractionAction> savedActions = new HashMap<>();
    private final List<InteractionEntry> addedEntries = new ArrayList<>();
    private final List<MenuActionAccess.Resolver> addedResolvers = new ArrayList<>();
    private MockedStatic<PortalInteractHandlers> portal;
    private ServerPlayer player;
    private FakeMenu menu;
    private Slot target;
    private ItemStack original;
    private InteractionHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        for (String action : List.of(ACTION, POUR, SCOOP)) {
            savedActions.put(action, registeredActions().get(action));
        }
        portal = mockStatic(PortalInteractHandlers.class);

        player = mock(ServerPlayer.class);
        handler = mock(InteractionHandler.class);
        menu = new FakeMenu(17);
        target = new Slot(new SimpleContainer(9), PHYSICAL, 0, 0);
        target.set(living());
        menu.attach(target);
        player.containerMenu = menu;
        original = new ItemStack(Items.STICK, 7);
        menu.setCarried(original);
        menu.carriedWrites = 0;
        InteractionRegistry.registerHandler(ACTION, handler);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (portal != null) portal.close();
        } finally {
            registeredEntries().removeAll(addedEntries);
            registeredResolvers().removeAll(addedResolvers);
            Map<String, InteractionAction> actions = registeredActions();
            savedActions.forEach((id, action) -> {
                if (action == null) actions.remove(id);
                else actions.put(id, action);
            });
        }
    }

    @Test
    void oldMenuId_rejectsBeforeCursorParseOrMaintenance() {
        assertRejected(new GuiInteractionPacket(0, PHYSICAL, ACTION, new CompoundTag(), 16));
        assertEquals(0, menu.carriedWrites);
        verifyNoInteractions(player);
    }

    @Test
    void invalidMenu_rejectsWithoutMaintenance() {
        menu.valid = false;
        assertRejected(packet(0, PHYSICAL, ACTION, null));
    }

    @Test
    void inactiveSlot_rejectsWithoutFallingBack() {
        menu = new FakeMenu(17);
        target = new Slot(new SimpleContainer(9), PHYSICAL, 0, 0) {
            @Override public boolean isActive() { return false; }
        };
        target.set(living());
        menu.attach(target);
        menu.attach(new Slot(new SimpleContainer(living()), 0, 0, 0));
        player.containerMenu = menu;
        menu.setCarried(original);
        menu.carriedWrites = 0;
        assertRejected(packet(0, PHYSICAL, ACTION, null));
    }

    @Test
    void invalidMenuIndex_rejectsEvenWhenPhysicalSlotExists() {
        assertRejected(packet(-1, PHYSICAL, ACTION, null));
        assertRejected(packet(menu.slots.size(), PHYSICAL, ACTION, null));
    }

    @Test
    void mismatchedPhysicalIndex_doesNotChooseOtherInventory() {
        Slot otherInventory = new Slot(new SimpleContainer(9), 5, 0, 0);
        otherInventory.set(living());
        menu.attach(otherInventory);
        assertRejected(packet(0, 5, ACTION, null));
        assertTrue(otherInventory.getItem().is(Items.LEVER));
    }

    @Test
    void samePhysicalIndexInDifferentInventories_executesOnlyExactTarget() {
        Slot otherInventory = new Slot(new SimpleContainer(9), PHYSICAL, 0, 0);
        otherInventory.set(living());
        menu.attach(otherInventory);

        GuiInteractionPacket.handle(packet(1, PHYSICAL, ACTION, null), player);

        verify(handler).handle(player, otherInventory);
        org.mockito.Mockito.verifyNoMoreInteractions(handler);
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, otherInventory));
        assertSame(original, menu.getCarried());
        assertEquals(1, menu.broadcasts);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void sharedHandlerAliases_packetSelectsExactDisplaySlotAndSingleLogicalScope(int selected) {
        HandlerContext context = useHandlerAliases();
        Slot selectedTarget = menu.getSlot(selected);
        ExactAccess access = new ExactAccess(menu, context, MenuActionAccess.Decision.ALLOW);
        registerResolver(access);
        InteractionRegistry.registerHandler(ACTION, InteractionAction.container(handler));

        try (MockedStatic<ContainerContexts> contexts = resolveAliases(context)) {
            assertSame(context.storage, ((SlotItemHandler) menu.getSlot(0)).getItemHandler());
            assertSame(context.storage, ((SlotItemHandler) menu.getSlot(1)).getItemHandler());
            assertEquals(ALIAS_PHYSICAL, menu.getSlot(0).getContainerSlot());
            assertEquals(ALIAS_PHYSICAL, menu.getSlot(1).getContainerSlot());
            GuiInteractionPacket.handle(packet(selected, ALIAS_PHYSICAL, ACTION, null), player);
        }

        verify(handler).handle(player, selectedTarget);
        org.mockito.Mockito.verifyNoMoreInteractions(handler);
        assertEquals(1, access.requests.size());
        assertSame(selectedTarget, access.requests.getFirst().target());
        assertEquals(selected, access.requests.getFirst().target().index);
        assertEquals(Set.of(ALIAS_PHYSICAL), access.requests.getFirst().logicalSlots());
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, selectedTarget));
        portal.verifyNoMoreInteractions();
        assertSame(original, menu.getCarried());
        assertEquals(1, menu.broadcasts);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void sharedHandlerAliases_withoutAdapterRemainUnknownAndPacketDoesNotExecute(int selected) {
        HandlerContext context = useHandlerAliases();
        Slot selectedTarget = menu.getSlot(selected);
        InteractionRegistry.registerHandler(ACTION, InteractionAction.container(handler));

        try (MockedStatic<ContainerContexts> contexts = resolveAliases(context)) {
            MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, selectedTarget,
                InteractionAction.Kind.CONTAINER_STATE, context, Set.of(ALIAS_PHYSICAL));
            assertSame(selectedTarget, MenuActionAccess.scopeSlot(request, ALIAS_PHYSICAL));
            assertEquals(MenuActionAccess.Decision.UNKNOWN, MenuActionAccess.check(request));
            assertRejected(packet(selected, ALIAS_PHYSICAL, ACTION, null));
        }
        assertTrue(context.storage.getStackInSlot(ALIAS_PHYSICAL).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void sharedHandlerAliases_wrongSelectedOwnerCannotBorrowSameNumberAuthorizedAlias(int selected) {
        HandlerContext requested = useHandlerAliases();
        HandlerContext foreign = new HandlerContext();
        Slot selectedTarget = menu.getSlot(selected);
        Slot authorizedAlias = menu.getSlot(1 - selected);
        ExactAccess access = new ExactAccess(menu, requested, MenuActionAccess.Decision.ALLOW);
        registerResolver(access);
        InteractionRegistry.registerHandler(ACTION, new InteractionAction(
            InteractionAction.Kind.CONTAINER_STATE, handler,
            (p, slot, carried) -> new InteractionAction.Scope(requested, Set.of(ALIAS_PHYSICAL))));

        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            contexts.when(() -> ContainerContexts.resolve(player, selectedTarget)).thenReturn(foreign);
            contexts.when(() -> ContainerContexts.resolve(player, authorizedAlias)).thenReturn(requested);
            assertEquals(selectedTarget.getContainerSlot(), authorizedAlias.getContainerSlot());
            assertRejected(packet(selected, ALIAS_PHYSICAL, ACTION, null));
        }
        assertTrue(access.requests.isEmpty());
        assertTrue(requested.storage.getStackInSlot(ALIAS_PHYSICAL).isEmpty());
        assertTrue(foreign.storage.getStackInSlot(ALIAS_PHYSICAL).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void realBucketPackets_pourAndScoopThroughOppositeAliasesConserveWater(int selected) {
        HandlerContext context = useHandlerAliases();
        ExactAccess access = registerBucketAccess(context, MenuActionAccess.Decision.ALLOW);
        ContainerFluidData data = ContainerFluidData.getOrCreate(context);
        FluidSourceLedger ledger = data;
        ItemStack fullBucket = living(Items.WATER_BUCKET);
        menu.setCarried(fullBucket);
        menu.carriedWrites = 0;
        Slot pouredAt = menu.getSlot(selected);
        Slot scoopedAt = menu.getSlot(1 - selected);

        try (MockedStatic<ContainerContexts> contexts = resolveAliases(context)) {
            GuiInteractionPacket.handle(packet(selected, ALIAS_PHYSICAL, POUR, null), player);

            assertSame(data, ContainerFluidData.getOrCreate(context));
            assertEquals(Map.of(ALIAS_PHYSICAL, Fluids.WATER.getFluidType()), data.getGeneratedSources());
            assertSame(Fluids.WATER.getFluidType(), ledger.sourceAt(ALIAS_PHYSICAL));
            assertTrue(menu.getCarried().is(Items.BUCKET));
            assertEquals(1, menu.getCarried().getCount());
            assertTrue(LivingItemManager.isLivingItem(menu.getCarried()));
            assertTrue(context.getItem(ALIAS_PHYSICAL).isEmpty());
            assertSame(context.getItem(ALIAS_PHYSICAL), pouredAt.getItem());
            assertSame(pouredAt.getItem(), scoopedAt.getItem());
            assertTrue(fullBucket.is(Items.WATER_BUCKET));

            data.tick(context);
            assertSame(Fluids.WATER.getFluidType(), data.sourceFluid(ALIAS_PHYSICAL));
            GuiInteractionPacket.handle(packet(1 - selected, ALIAS_PHYSICAL, SCOOP, null), player);

            assertNull(ledger.sourceAt(ALIAS_PHYSICAL));
            assertTrue(data.getGeneratedSources().isEmpty());
            assertTrue(menu.getCarried().is(Items.WATER_BUCKET));
            assertEquals(1, menu.getCarried().getCount());
            assertTrue(LivingItemManager.isLivingItem(menu.getCarried()));
            data.tick(context);
            assertNull(data.sourceFluid(ALIAS_PHYSICAL));
        }

        assertEquals(2, access.requests.size());
        assertSame(pouredAt, access.requests.get(0).target());
        assertSame(scoopedAt, access.requests.get(1).target());
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, pouredAt));
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, scoopedAt));
        portal.verifyNoMoreInteractions();
        assertEquals(2, menu.carriedWrites);
        assertEquals(2, menu.broadcasts);
        verifyNoInteractions(handler);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void realBucketPackets_creativePourDoesNotConsumeAndScoopStillClearsSource(int selected) {
        HandlerContext context = useHandlerAliases();
        ExactAccess access = registerBucketAccess(context, MenuActionAccess.Decision.ALLOW);
        ContainerFluidData data = ContainerFluidData.getOrCreate(context);
        FluidSourceLedger ledger = data;
        when(player.hasInfiniteMaterials()).thenReturn(true);
        ItemStack fullBucket = living(Items.WATER_BUCKET);
        menu.setCarried(fullBucket);

        try (MockedStatic<ContainerContexts> contexts = resolveAliases(context)) {
            GuiInteractionPacket.handle(packet(selected, ALIAS_PHYSICAL, POUR, null), player);
            assertSame(fullBucket, menu.getCarried());
            assertEquals(1, fullBucket.getCount());
            assertTrue(fullBucket.is(Items.WATER_BUCKET));
            assertSame(Fluids.WATER.getFluidType(), ledger.sourceAt(ALIAS_PHYSICAL));
            data.tick(context);

            menu.setCarried(living(Items.BUCKET));
            GuiInteractionPacket.handle(packet(1 - selected, ALIAS_PHYSICAL, SCOOP, null), player);
            assertNull(ledger.sourceAt(ALIAS_PHYSICAL));
            assertTrue(menu.getCarried().is(Items.WATER_BUCKET));
            assertEquals(1, menu.getCarried().getCount());
            assertTrue(LivingItemManager.isLivingItem(menu.getCarried()));
        }
        assertEquals(2, access.requests.size());
        Slot pouredAt = menu.getSlot(selected);
        Slot scoopedAt = menu.getSlot(1 - selected);
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, pouredAt));
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, scoopedAt));
        portal.verifyNoMoreInteractions();
        assertEquals(2, menu.broadcasts);
        verifyNoInteractions(handler);
    }

    @ParameterizedTest
    @CsvSource({
        "0, true, DENY", "1, true, DENY", "0, true, UNKNOWN", "1, true, UNKNOWN",
        "0, false, DENY", "1, false, DENY", "0, false, UNKNOWN", "1, false, UNKNOWN"
    })
    void realBucketPackets_deniedOrUnknownPreserveBucketAndSourceWithoutMaintenance(
            int selected, boolean pour, MenuActionAccess.Decision decision) {
        HandlerContext context = useHandlerAliases();
        ExactAccess access = registerBucketAccess(context, decision);
        assertBucketPacketRejected(context, selected, pour);
        assertEquals(1, access.requests.size());
        assertSame(menu.getSlot(selected), access.requests.getFirst().target());
    }

    @ParameterizedTest
    @CsvSource({"0, true", "1, true", "0, false", "1, false"})
    void realBucketPackets_withoutAdapterPreserveBucketAndSource(int selected, boolean pour) {
        HandlerContext context = useHandlerAliases();
        registerBucketActions();
        assertBucketPacketRejected(context, selected, pour);
    }

    @Test
    void unknownHandler_rejectsWithoutMaintenance() {
        assertRejected(packet(0, PHYSICAL, "unregistered_dispatch_action", null));
    }

    @Test
    void itemAction_rejectsEmptyAndNonLivingTargets() {
        target.set(ItemStack.EMPTY);
        assertRejected(packet(0, PHYSICAL, ACTION, null));
        target.set(new ItemStack(Items.LEVER));
        assertRejected(packet(0, PHYSICAL, ACTION, null));
    }

    @Test
    void itemAction_rejectsDifferentLivingItemDeclaredByRule() {
        InteractionEntry entry = new InteractionEntry(Items.STONE_BUTTON, null, 1, ACTION);
        addedEntries.add(entry);
        InteractionRegistry.register(entry);
        assertRejected(packet(0, PHYSICAL, ACTION, null));
    }

    @ParameterizedTest
    @EnumSource(value = MenuActionAccess.Decision.class, names = {"DENY", "UNKNOWN"})
    void claimedPermissionRejection_neverFallsBackToOrdinarySlot(MenuActionAccess.Decision decision) {
        MenuActionAccess.Resolver resolver = registerAccess(decision);
        assertRejected(packet(0, PHYSICAL, ACTION, null));
        verify(resolver).check(isA(MenuActionAccess.Request.class));
    }

    @Test
    void normalItemAction_executesExactlyOnceAndMaintainsAfterward() {
        GuiInteractionPacket.handle(packet(0, PHYSICAL, ACTION, null), player);

        verify(handler).handle(player, target);
        org.mockito.Mockito.verifyNoMoreInteractions(handler);
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, target));
        portal.verifyNoMoreInteractions();
        assertSame(original, menu.getCarried());
        assertEquals(0, menu.carriedWrites);
        assertEquals(1, menu.broadcasts);
    }

    @Test
    void emptyContainerAction_usesExplicitKindAndScopeWithRegisteredAccess() {
        SimpleContainer storage = new SimpleContainer(9);
        target = new Slot(storage, PHYSICAL, 0, 0);
        Slot otherScopedSlot = new Slot(storage, 5, 0, 0);
        menu = new FakeMenu(17);
        menu.attach(target);
        menu.attach(otherScopedSlot);
        player.containerMenu = menu;
        menu.setCarried(original);
        menu.carriedWrites = 0;
        var context = mock(com.qiqi.li.living.container.TickableContainerContext.class);
        when(context.getContainerKey()).thenReturn("test_dispatch_scope");
        when(context.getLevel()).thenReturn(mock(net.minecraft.server.level.ServerLevel.class));
        when(context.toLogicalSlot(PHYSICAL)).thenReturn(2);
        when(context.toLogicalSlot(5)).thenReturn(3);
        when(context.toPhysicalSlot(2)).thenReturn(PHYSICAL);
        when(context.toPhysicalSlot(3)).thenReturn(5);
        when(context.isValidSlot(2)).thenReturn(true);
        when(context.isValidSlot(3)).thenReturn(true);
        InteractionAction.Scope scope = new InteractionAction.Scope(context, Set.of(2, 3));
        AtomicReference<MenuActionAccess.Request> checked = new AtomicReference<>();
        registerResolver(new MenuActionAccess.Resolver() {
            @Override public boolean supports(AbstractContainerMenu candidate) { return candidate == menu; }
            @Override public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
                checked.set(request);
                return MenuActionAccess.Decision.ALLOW;
            }
        });
        InteractionRegistry.registerHandler(ACTION, new InteractionAction(
            InteractionAction.Kind.CONTAINER_STATE, handler, (p, slot, carried) -> scope));

        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            contexts.when(() -> ContainerContexts.resolve(player, target)).thenReturn(context);
            contexts.when(() -> ContainerContexts.resolve(player, otherScopedSlot)).thenReturn(context);
            GuiInteractionPacket.handle(packet(0, PHYSICAL, ACTION, null), player);
        }

        verify(handler).handle(player, target);
        assertEquals(InteractionAction.Kind.CONTAINER_STATE, checked.get().kind());
        assertSame(context, checked.get().context());
        assertEquals(Set.of(2, 3), checked.get().logicalSlots());
        assertSame(target, checked.get().target());
        assertTrue(target.getItem().isEmpty());
        assertSame(original, menu.getCarried());
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, target));
        assertEquals(1, menu.broadcasts);
    }

    @Test
    void emptyContainerAction_withoutResolvedScopeRejects() {
        target.set(ItemStack.EMPTY);
        InteractionRegistry.registerHandler(ACTION, new InteractionAction(
            InteractionAction.Kind.CONTAINER_STATE, handler, (p, slot, carried) -> null));
        assertRejected(packet(0, PHYSICAL, ACTION, null));
    }

    @Test
    void creativeCarriedTagOnNonInventoryMenu_isRejectedBeforeParse() {
        when(player.isCreative()).thenReturn(true);
        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
            assertRejected(packet(0, PHYSICAL, ACTION, new CompoundTag()));
            stacks.verifyNoInteractions();
        }
        assertEquals(0, menu.carriedWrites);
        verify(player, org.mockito.Mockito.never()).registryAccess();
    }

    @Test
    void nonCreativeCarriedTag_isRejectedBeforeParse() {
        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
            assertRejected(packet(0, PHYSICAL, ACTION, new CompoundTag()));
            stacks.verifyNoInteractions();
        }
        assertEquals(0, menu.carriedWrites);
        verify(player, org.mockito.Mockito.never()).registryAccess();
    }

    @ParameterizedTest
    @EnumSource(value = MenuActionAccess.Decision.class, names = {"DENY", "UNKNOWN"})
    void creativeCandidateDenied_preservesOriginalBeforeBorrowing(MenuActionAccess.Decision decision)
            throws Exception {
        InventoryMenu inventoryMenu = useInventoryMenu();
        when(player.isCreative()).thenReturn(true);
        RegistryAccess registry = mock(RegistryAccess.class);
        when(player.registryAccess()).thenReturn(registry);
        registerAccess(decision);
        ItemStack candidate = new ItemStack(Items.BONE_MEAL, 3);
        CompoundTag tag = new CompoundTag();
        AtomicReference<ItemStack> scopedCandidate = new AtomicReference<>();
        InteractionRegistry.registerHandler(ACTION, new InteractionAction(
            InteractionAction.Kind.ITEM_STATE, handler, (p, slot, carried) -> {
                assertSame(original, inventoryMenu.getCarried());
                scopedCandidate.set(carried);
                return new InteractionAction.Scope(null, Set.of());
            }));

        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.parse(registry, tag)).thenReturn(Optional.of(candidate));
            GuiInteractionPacket.handle(new GuiInteractionPacket(36, 0, ACTION, tag, 0), player);
            stacks.verify(() -> ItemStack.parse(registry, tag));
        }

        assertSame(candidate, scopedCandidate.get());
        assertSame(original, inventoryMenu.getCarried());
        assertEquals(7, original.getCount());
        assertEquals(0, ((RecordingInventoryMenu) inventoryMenu).carriedWrites);
        assertEquals(0, ((RecordingInventoryMenu) inventoryMenu).broadcasts);
        verifyNoInteractions(handler);
        portal.verifyNoInteractions();
    }

    @Test
    void creativeCandidate_isBorrowedOnlyAfterAuthorizationAndOriginalIsRestored() throws Exception {
        RecordingInventoryMenu inventoryMenu = useInventoryMenu();
        when(player.isCreative()).thenReturn(true);
        RegistryAccess registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        when(player.registryAccess()).thenReturn(registry);
        CompoundTag tag = (CompoundTag) new ItemStack(Items.BONE_MEAL, 3).saveOptional(registry);
        AtomicReference<ItemStack> candidate = new AtomicReference<>();
        InteractionRegistry.registerHandler(ACTION, new InteractionAction(
            InteractionAction.Kind.ITEM_STATE, (p, slot) -> {
                assertSame(candidate.get(), inventoryMenu.getCarried());
                inventoryMenu.getCarried().shrink(1);
                handler.handle(p, slot);
            }, (p, slot, carried) -> {
                assertSame(original, inventoryMenu.getCarried());
                assertTrue(carried.is(Items.BONE_MEAL));
                assertEquals(3, carried.getCount());
                candidate.set(carried);
                return new InteractionAction.Scope(null, Set.of());
            }));
        registerAccess(MenuActionAccess.Decision.ALLOW);

        try (MockedStatic<PacketDistributor> packets = mockStatic(PacketDistributor.class)) {
            GuiInteractionPacket.handle(new GuiInteractionPacket(36, 0, ACTION, tag, 0), player);
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), isA(CarriedUpdatePacket.class)));
        }

        verify(handler).handle(player, inventoryMenu.getSlot(36));
        org.mockito.Mockito.verifyNoMoreInteractions(handler);
        portal.verify(() -> PortalInteractHandlers.afterInteraction(player, inventoryMenu.getSlot(36)));
        assertSame(original, inventoryMenu.getCarried());
        assertEquals(7, original.getCount());
        assertEquals(2, candidate.get().getCount());
        assertEquals(2, inventoryMenu.carriedWrites);
        assertEquals(1, inventoryMenu.broadcasts);
    }

    @Test
    void creativeCandidate_handlerFailureStillRestoresOriginal() throws Exception {
        RecordingInventoryMenu inventoryMenu = useInventoryMenu();
        when(player.isCreative()).thenReturn(true);
        RegistryAccess registry = mock(RegistryAccess.class);
        when(player.registryAccess()).thenReturn(registry);
        CompoundTag tag = new CompoundTag();
        ItemStack candidate = new ItemStack(Items.BONE_MEAL, 3);
        InteractionRegistry.registerHandler(ACTION, (p, slot) -> {
            assertSame(candidate, inventoryMenu.getCarried());
            throw new IllegalStateException("test handler failure");
        });
        registerAccess(MenuActionAccess.Decision.ALLOW);

        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.parse(registry, tag)).thenReturn(Optional.of(candidate));
            assertThrows(IllegalStateException.class, () -> GuiInteractionPacket.handle(
                new GuiInteractionPacket(36, 0, ACTION, tag, 0), player));
        }

        assertSame(original, inventoryMenu.getCarried());
        assertEquals(2, inventoryMenu.carriedWrites);
        assertEquals(0, inventoryMenu.broadcasts);
        portal.verifyNoInteractions();
    }

    private HandlerContext useHandlerAliases() {
        HandlerContext context = new HandlerContext();
        menu = new FakeMenu(17);
        menu.attach(new SlotItemHandler(context.storage, ALIAS_PHYSICAL, 0, 0));
        menu.attach(new SlotItemHandler(context.storage, ALIAS_PHYSICAL, 18, 0));
        player.containerMenu = menu;
        when(player.level()).thenReturn(context.level);
        menu.setCarried(original);
        menu.carriedWrites = 0;
        return context;
    }

    private MockedStatic<ContainerContexts> resolveAliases(HandlerContext context) {
        MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class);
        for (Slot alias : menu.slots) {
            contexts.when(() -> ContainerContexts.resolve(player, alias)).thenReturn(context);
        }
        return contexts;
    }

    private void registerBucketActions() {
        InteractionRegistry.registerHandler(POUR, LivingBucketInteractHandlers.POUR_ACTION);
        InteractionRegistry.registerHandler(SCOOP, LivingBucketInteractHandlers.SCOOP_ACTION);
    }

    private ExactAccess registerBucketAccess(HandlerContext context, MenuActionAccess.Decision decision) {
        registerBucketActions();
        ExactAccess access = new ExactAccess(menu, context, decision);
        registerResolver(access);
        return access;
    }

    private void assertBucketPacketRejected(HandlerContext context, int selected, boolean pour) {
        ContainerFluidData data = ContainerFluidData.getOrCreate(context);
        FluidSourceLedger ledger = data;
        if (!pour) {
            data.registerGeneratedSource(ALIAS_PHYSICAL, Fluids.WATER.getFluidType());
            data.tick(context);
            assertSame(Fluids.WATER.getFluidType(), data.sourceFluid(ALIAS_PHYSICAL));
        }
        ItemStack bucket = living(pour ? Items.WATER_BUCKET : Items.BUCKET);
        menu.setCarried(bucket);
        menu.carriedWrites = 0;
        ItemStack before = bucket.copy();
        Map<Integer, net.neoforged.neoforge.fluids.FluidType> beforeSources =
            new HashMap<>(data.getGeneratedSources());
        Map<Integer, ContainerFluidData.FlowEntry> beforeFlows = new HashMap<>(data.getFlows());
        Slot selectedTarget = menu.getSlot(selected);

        try (MockedStatic<ContainerContexts> contexts = resolveAliases(context)) {
            GuiInteractionPacket.handle(packet(selected, ALIAS_PHYSICAL, pour ? POUR : SCOOP, null), player);
        }
        assertSame(bucket, menu.getCarried());
        assertTrue(ItemStack.isSameItemSameComponents(before, bucket));
        assertEquals(before.getCount(), bucket.getCount());
        assertEquals(beforeSources, data.getGeneratedSources());
        assertEquals(beforeFlows, data.getFlows());
        assertSame(pour ? null : Fluids.WATER.getFluidType(), ledger.sourceAt(ALIAS_PHYSICAL));
        assertTrue(context.storage.getStackInSlot(ALIAS_PHYSICAL).isEmpty());
        assertTrue(selectedTarget.getItem().isEmpty());
        assertEquals(0, menu.carriedWrites);
        assertEquals(0, menu.broadcasts);
        portal.verifyNoInteractions();
        verifyNoInteractions(handler);
    }

    private GuiInteractionPacket packet(int index, int physical, String action, CompoundTag tag) {
        return new GuiInteractionPacket(index, physical, action, tag, menu.containerId);
    }

    private void assertRejected(GuiInteractionPacket packet) {
        GuiInteractionPacket.handle(packet, player);
        verifyNoInteractions(handler);
        portal.verifyNoInteractions();
        assertSame(original, menu.getCarried());
        assertEquals(7, original.getCount());
        assertEquals(0, menu.carriedWrites);
        assertEquals(0, menu.broadcasts);
    }

    private void registerResolver(MenuActionAccess.Resolver resolver) {
        addedResolvers.add(resolver);
        MenuActionAccess.register(resolver);
    }

    private MenuActionAccess.Resolver registerAccess(MenuActionAccess.Decision decision) {
        MenuActionAccess.Resolver resolver = mock(MenuActionAccess.Resolver.class);
        when(resolver.supports(player.containerMenu)).thenReturn(true);
        when(resolver.check(isA(MenuActionAccess.Request.class))).thenReturn(decision);
        registerResolver(resolver);
        return resolver;
    }

    private RecordingInventoryMenu useInventoryMenu() throws Exception {
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        RecordingInventoryMenu inventoryMenu = new RecordingInventoryMenu(inventory, player);
        Field field = Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(player, inventoryMenu);
        player.containerMenu = inventoryMenu;
        inventory.setItem(0, living());
        inventoryMenu.setCarried(original);
        inventoryMenu.carriedWrites = 0;
        return inventoryMenu;
    }

    private static ItemStack living() {
        return living(Items.LEVER);
    }

    private static ItemStack living(Item item) {
        ItemStack stack = new ItemStack(item);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, InteractionAction> registeredActions() throws Exception {
        Field field = InteractionRegistry.class.getDeclaredField("HANDLERS");
        field.setAccessible(true);
        return (Map<String, InteractionAction>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<MenuActionAccess.Resolver> registeredResolvers() throws Exception {
        Field field = MenuActionAccess.class.getDeclaredField("RESOLVERS");
        field.setAccessible(true);
        return (List<MenuActionAccess.Resolver>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<InteractionEntry> registeredEntries() throws Exception {
        Field field = InteractionRegistry.class.getDeclaredField("ENTRIES");
        field.setAccessible(true);
        return (List<InteractionEntry>) field.get(null);
    }

    /** 我方存储与容器数据夹具；显示别名共享 handler 和同一真实流体数据。 */
    private static final class HandlerContext extends FakeContainerContext implements TickableContainerContext {
        final ItemStackHandler storage = new ItemStackHandler(9);
        final ServerLevel level = mock(ServerLevel.class);
        private final ContainerDataStore data = new ContainerDataStore();

        HandlerContext() {
            super(9, 3, "dispatch_alias_" + UUID.randomUUID());
            when(level.dimension()).thenReturn(Level.OVERWORLD);
        }

        @Override public Level getLevel() { return level; }
        @Override public ItemStack getItem(int logical) {
            return isValidSlot(logical) ? storage.getStackInSlot(toPhysicalSlot(logical)) : ItemStack.EMPTY;
        }
        @Override public void setItem(int logical, ItemStack stack) {
            storage.setStackInSlot(toPhysicalSlot(logical), stack);
        }
        @Override public <T> T peekContainerData(ContainerDataKey<T> key) { return data.peek(key); }
        @Override public <T> T getOrCreateContainerData(ContainerDataKey<T> key) { return data.getOrCreate(key); }
        @Override public void setTickContext(TickContext tick) {}
        @Override public void flushDirtySlots() {}
        @Override public List<BlockEntity> getAssociatedBlockEntities() { return List.of(); }
        @Override public Inventory getInventory() { return null; }
    }

    /** 我方访问接口仅认领菜单实例；只读核验精确 target、宿主与单槽范围。 */
    private static final class ExactAccess implements MenuActionAccess.Resolver {
        final List<MenuActionAccess.Request> requests = new ArrayList<>();
        private final AbstractContainerMenu menu;
        private final ContainerContext context;
        private final MenuActionAccess.Decision decision;

        ExactAccess(AbstractContainerMenu menu, ContainerContext context, MenuActionAccess.Decision decision) {
            this.menu = menu;
            this.context = context;
            this.decision = decision;
        }

        @Override public boolean supports(AbstractContainerMenu candidate) { return candidate == menu; }
        @Override public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
            assertSame(menu, request.menu());
            assertSame(context, request.context());
            assertSame(menu.getSlot(request.target().index), request.target());
            assertEquals(InteractionAction.Kind.CONTAINER_STATE, request.kind());
            assertEquals(Set.of(ALIAS_PHYSICAL), request.logicalSlots());
            assertEquals(ALIAS_PHYSICAL, request.target().getContainerSlot());
            assertSame(request.target(), MenuActionAccess.scopeSlot(request, ALIAS_PHYSICAL));
            requests.add(request);
            return decision;
        }
    }

    private static class FakeMenu extends AbstractContainerMenu {
        boolean valid = true;
        int carriedWrites;
        int broadcasts;
        FakeMenu(int id) { super(null, id); }
        void attach(Slot slot) { addSlot(slot); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return valid; }
        @Override public void setCarried(ItemStack stack) { carriedWrites++; super.setCarried(stack); }
        @Override public void broadcastChanges() { broadcasts++; super.broadcastChanges(); }
    }

    private static class RecordingInventoryMenu extends InventoryMenu {
        int carriedWrites;
        int broadcasts;
        RecordingInventoryMenu(Inventory inventory, Player owner) { super(inventory, false, owner); }
        @Override public void setCarried(ItemStack stack) { carriedWrites++; super.setCarried(stack); }
        @Override public void broadcastChanges() { broadcasts++; super.broadcastChanges(); }
    }
}
