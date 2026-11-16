package com.qiqi.li.living.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** 菜单动作访问边界；真实物品存取结果不替代状态授权。 */
class MenuActionAccessTest {
    private static final InteractionHandler UNUSED_HANDLER = (player, target) -> {};
    private final List<MenuActionAccess.Resolver> addedResolvers = new ArrayList<>();
    private List<MenuActionAccess.Resolver> resolvers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws ReflectiveOperationException {
        Field field = MenuActionAccess.class.getDeclaredField("RESOLVERS");
        field.setAccessible(true);
        resolvers = (List<MenuActionAccess.Resolver>) field.get(null);
    }

    @AfterEach
    void tearDown() {
        if (resolvers != null) resolvers.removeAll(addedResolvers);
    }

    private void registerResolver(MenuActionAccess.Resolver resolver) {
        addedResolvers.add(resolver);
        MenuActionAccess.register(resolver);
    }

    @Test
    void emptyRealHandlerSlotIsUnknownRatherThanPickupAuthorized() {
        ItemStackHandler storage = new ItemStackHandler(1);
        Slot target = new SlotItemHandler(storage, 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);

        assertTrue(target.isActive());
        assertTrue(menu.stillValid(player));
        assertTrue(target.getItem().isEmpty());
        assertTrue(storage.extractItem(0, 1, true).isEmpty());
        assertFalse(target.mayPickup(player));
        assertTrue(target.mayPlace(new ItemStack(Items.STONE)));
        assertTrue(MenuActionAccess.validTarget(player, target));
        assertEquals(MenuActionAccess.Decision.UNKNOWN, check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertTrue(storage.getStackInSlot(0).isEmpty());
        verifyNoInteractions(player);
    }

    @ParameterizedTest
    @EnumSource(value = InteractionAction.Kind.class, names = {"CONTAINER_STATE", "USE_CONTAINER"})
    void explicitResolverCanAuthorizeEmptyRealHandlerSlot(InteractionAction.Kind kind) {
        ItemStackHandler storage = new ItemStackHandler(13);
        Slot target = new SlotItemHandler(storage, 10, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);
        TickableContainerContext context = offsetContext("owner");
        Set<Integer> scope = Set.of(0);
        RecordingResolver resolver = new RecordingResolver(menu, request -> {
            assertSame(player, request.player());
            assertSame(menu, request.menu());
            assertSame(target, request.target());
            assertEquals(kind, request.kind());
            assertEquals(scope, request.logicalSlots());
            return MenuActionAccess.Decision.ALLOW;
        });
        registerResolver(resolver);
        InteractionAction action = new InteractionAction(kind, UNUSED_HANDLER,
            (p, slot, carried) -> new InteractionAction.Scope(context, scope));

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, target)).thenReturn(context);
            assertFalse(target.mayPickup(player));
            assertEquals(MenuActionAccess.Decision.ALLOW, MenuActionAccess.check(
                new MenuActionAccess.Request(player, menu, target, kind, context, scope)));
            assertTrue(action.permits(player, target, ItemStack.EMPTY));
        }
        assertEquals(2, resolver.checks.get());
        assertTrue(storage.getStackInSlot(10).isEmpty());
        verifyNoInteractions(player);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void duplicateRealHandlerTargetUsesSelectedExactAliasWithoutInferringAccess(int selected) {
        ItemStackHandler storage = new ItemStackHandler(9);
        SlotItemHandler first = new SlotItemHandler(storage, 5, 0, 0);
        SlotItemHandler second = new SlotItemHandler(storage, 5, 18, 0);
        TestMenu menu = new TestMenu(first, second);
        ServerPlayer player = playerFor(menu);
        Slot target = menu.getSlot(selected);
        TickableContainerContext context = new IdentityContext("alias_owner");
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(5));
        InteractionAction action = InteractionAction.container(UNUSED_HANDLER);

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, first)).thenReturn(context);
            owners.when(() -> ContainerContexts.resolve(player, second)).thenReturn(context);
            assertSame(storage, first.getItemHandler());
            assertSame(storage, second.getItemHandler());
            assertEquals(5, first.getContainerSlot());
            assertEquals(5, second.getContainerSlot());
            assertTrue(target.isActive());
            assertFalse(target.mayPickup(player));
            assertSame(target, MenuActionAccess.scopeSlot(request, 5));
            assertEquals(MenuActionAccess.Decision.UNKNOWN, MenuActionAccess.check(request));
            assertFalse(action.permits(player, target, ItemStack.EMPTY));

            RecordingResolver access = new RecordingResolver(menu, checked -> {
                assertSame(target, checked.target());
                assertEquals(selected, checked.target().index);
                assertEquals(Set.of(5), checked.logicalSlots());
                assertSame(context, checked.context());
                return MenuActionAccess.Decision.ALLOW;
            });
            registerResolver(access);
            assertEquals(MenuActionAccess.Decision.ALLOW, MenuActionAccess.check(request));
            assertTrue(action.permits(player, target, ItemStack.EMPTY));
            assertEquals(2, access.checks.get());
        }
        assertTrue(storage.getStackInSlot(5).isEmpty());
        verifyNoInteractions(player);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void duplicateStandardTargetAllowsSelectedAliasButOtherScopeSlotsStillRequireUniqueness(int selected) {
        SimpleContainer storage = new SimpleContainer(13);
        Slot first = new Slot(storage, 11, 0, 0);
        Slot second = new Slot(storage, 11, 18, 0);
        TestMenu menu = new TestMenu(first, second);
        ServerPlayer player = playerFor(menu);
        Slot target = menu.getSlot(selected);
        ContainerContext context = offsetContext("owner");
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(1));

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, first)).thenReturn(context);
            owners.when(() -> ContainerContexts.resolve(player, second)).thenReturn(context);
            assertSame(target, MenuActionAccess.scopeSlot(request, 1));
            assertEquals(MenuActionAccess.Decision.ALLOW, MenuActionAccess.check(request));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_owner", "wrong_level", "missing_owner", "inactive", "detached", "roundtrip"})
    void exactAliasCannotBorrowAnotherDisplaySlotsOwnerOrLifecycle(String condition) {
        ItemStackHandler storage = new ItemStackHandler(9);
        Slot target = condition.equals("inactive") ? new SlotItemHandler(storage, 5, 0, 0) {
            @Override public boolean isActive() { return false; }
        } : new SlotItemHandler(storage, 5, 0, 0);
        Slot other = new SlotItemHandler(storage, 5, 18, 0);
        TestMenu menu = new TestMenu(target, other);
        ServerPlayer player = playerFor(menu);
        if (condition.equals("detached")) {
            menu.slots.set(0, new SlotItemHandler(storage, 5, 0, 0));
        }
        TickableContainerContext context = condition.equals("roundtrip")
            ? new IdentityContext("owner") {
                @Override public int toPhysicalSlot(int logical) { return logical + 1; }
            } : new IdentityContext("owner");
        TickableContainerContext resolved = switch (condition) {
            case "wrong_owner" -> new IdentityContext("foreign");
            case "wrong_level" -> {
                IdentityContext foreignLevel = new IdentityContext("owner");
                foreignLevel.withGameTime(0);
                yield foreignLevel;
            }
            case "missing_owner" -> null;
            default -> context;
        };
        RecordingResolver access = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(access);
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(5));

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, target)).thenReturn(resolved);
            owners.when(() -> ContainerContexts.resolve(player, other)).thenReturn(context);
            assertEquals(target.getContainerSlot(), other.getContainerSlot());
            assertNull(MenuActionAccess.scopeSlot(request, 5));
            assertEquals(MenuActionAccess.Decision.DENY, MenuActionAccess.check(request));
        }
        assertEquals(0, access.supports.get());
        assertTrue(storage.getStackInSlot(5).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void explicitAllowStillRejectsDuplicateNonTargetRealHandlerScopeSlot(int selected) {
        ItemStackHandler storage = new ItemStackHandler(9);
        SlotItemHandler first = new SlotItemHandler(storage, 5, 0, 0);
        SlotItemHandler second = new SlotItemHandler(storage, 5, 18, 0);
        Slot neighbor = new SlotItemHandler(storage, 6, 0, 18);
        Slot duplicateNeighbor = new SlotItemHandler(storage, 6, 18, 18);
        TestMenu menu = new TestMenu(first, second, neighbor, duplicateNeighbor);
        ServerPlayer player = playerFor(menu);
        TickableContainerContext context = new IdentityContext("owner");
        Slot target = menu.getSlot(selected);
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(5, 6));
        RecordingResolver access = new RecordingResolver(menu, checked -> MenuActionAccess.Decision.ALLOW);
        registerResolver(access);

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            for (Slot slot : menu.slots) {
                owners.when(() -> ContainerContexts.resolve(player, slot)).thenReturn(context);
            }
            assertSame(target, MenuActionAccess.scopeSlot(request, 5));
            assertNull(MenuActionAccess.scopeSlot(request, 6));
            assertEquals(MenuActionAccess.Decision.DENY, MenuActionAccess.check(request));
            assertFalse(new InteractionAction(request.kind(), UNUSED_HANDLER,
                (p, slot, carried) -> new InteractionAction.Scope(context, request.logicalSlots()))
                .permits(player, target, ItemStack.EMPTY));
        }
        assertEquals(0, access.supports.get());
    }

    @Test
    void realHandlerPickupAndInsertionDoNotProveStatePermission() {
        ItemStackHandler storage = new ItemStackHandler(1);
        ItemStack contents = living(Items.STONE, 3);
        storage.setStackInSlot(0, contents);
        Slot target = new SlotItemHandler(storage, 0, 0, 0);
        ServerPlayer player = playerFor(new TestMenu(target));

        assertTrue(target.mayPickup(player));
        assertTrue(target.mayPlace(new ItemStack(Items.STONE)));
        assertEquals(1, storage.extractItem(0, 1, true).getCount());
        assertTrue(storage.insertItem(0, living(Items.STONE, 1), true).isEmpty());
        assertEquals(MenuActionAccess.Decision.UNKNOWN, check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertSame(contents, storage.getStackInSlot(0));
        assertEquals(3, contents.getCount());
    }

    @Test
    void itemFilterRejectionIsNotAStatePermissionDenial() {
        ItemStackHandler storage = new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        };
        Slot target = new SlotItemHandler(storage, 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);
        ItemStack candidate = new ItemStack(Items.STONE);
        ItemStack contents = living(Items.LEVER, 1);
        storage.setStackInSlot(0, contents);

        assertFalse(target.mayPlace(candidate));
        assertTrue(target.mayPickup(player));
        assertSame(candidate, storage.insertItem(0, candidate, true));
        assertEquals(MenuActionAccess.Decision.UNKNOWN, check(player, target));
        registerResolver(new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW));
        assertTrue(InteractionAction.item(UNUSED_HANDLER).permits(player, target, candidate));
        assertSame(contents, storage.getStackInSlot(0));
        assertEquals(1, candidate.getCount());
    }

    @ParameterizedTest
    @EnumSource(InteractionAction.Kind.class)
    void exactStandardSlotAndSimpleContainerAllowStateActions(InteractionAction.Kind kind) {
        SimpleContainer storage = new SimpleContainer(1);
        Slot target = new Slot(storage, 0, 0, 0);
        ServerPlayer player = playerFor(new TestMenu(target));
        InteractionAction action = new InteractionAction(kind, UNUSED_HANDLER,
            (p, slot, carried) -> new InteractionAction.Scope(null, Set.of()));

        assertEquals(Slot.class, target.getClass());
        assertTrue(storage.stillValid(player));
        assertTrue(target.getItem().isEmpty());
        assertEquals(MenuActionAccess.Decision.ALLOW, MenuActionAccess.check(
            new MenuActionAccess.Request(player, player.containerMenu, target, kind, null, Set.of())));
        assertEquals(kind != InteractionAction.Kind.ITEM_STATE,
            action.permits(player, target, ItemStack.EMPTY));
        ItemStack contents = living(Items.STONE, 2);
        storage.setItem(0, contents);
        assertTrue(action.permits(player, target, ItemStack.EMPTY));
        assertSame(contents, storage.getItem(0));
        assertEquals(2, contents.getCount());
        verifyNoInteractions(player);
    }

    @Test
    void specialSlotSubclassIsUnknownDespiteOrdinaryStorageAndItemAccess() {
        SimpleContainer storage = new SimpleContainer(1);
        ItemStack contents = living(Items.LEVER, 1);
        storage.setItem(0, contents);
        Slot target = new Slot(storage, 0, 0, 0) {
            @Override
            public boolean mayPickup(Player player) {
                return true;
            }
        };
        ServerPlayer player = playerFor(new TestMenu(target));

        assertTrue(target.mayPickup(player));
        assertTrue(target.mayPlace(new ItemStack(Items.STONE)));
        assertTrue(storage.stillValid(player));
        assertEquals(MenuActionAccess.Decision.UNKNOWN, check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertSame(contents, storage.getItem(0));
    }

    @Test
    void inheritedPickupContractAllowsGenuineInventoryMenuOffhand() throws NoSuchMethodException {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory storage = new Inventory(player);
        when(player.canInteractWithEntity(player, 4.0)).thenReturn(true);
        InventoryMenu menu = new InventoryMenu(storage, true, player);
        player.containerMenu = menu;
        Slot target = menu.getSlot(InventoryMenu.SHIELD_SLOT);
        ItemStack contents = living(Items.LEVER, 1);
        storage.setItem(40, contents);

        assertFalse(target.getClass() == Slot.class);
        assertEquals(Slot.class, target.getClass().getMethod("mayPickup", Player.class).getDeclaringClass());
        assertSame(storage, target.container);
        assertEquals(40, target.getContainerSlot());
        assertTrue(storage.stillValid(player));
        assertEquals(MenuActionAccess.Decision.ALLOW, check(player, target));
        assertTrue(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertSame(contents, storage.getItem(40));
    }

    @Test
    void inheritedPickupContractAllowsGenuineFurnaceFuelSlot() throws NoSuchMethodException {
        SimpleContainer storage = new SimpleContainer(3);
        AbstractFurnaceMenu furnace = mock(AbstractFurnaceMenu.class);
        Slot target = new FurnaceFuelSlot(furnace, storage, 1, 0, 0);
        ServerPlayer player = playerFor(new TestMenu(target));
        ItemStack contents = living(Items.LEVER, 1);
        storage.setItem(1, contents);

        assertEquals(FurnaceFuelSlot.class, target.getClass());
        assertEquals(Slot.class, target.getClass().getMethod("mayPickup", Player.class).getDeclaringClass());
        assertSame(storage, target.container);
        assertEquals(1, target.getContainerSlot());
        assertEquals(MenuActionAccess.Decision.ALLOW, check(player, target));
        assertTrue(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertSame(contents, storage.getItem(1));
        verifyNoInteractions(furnace, player);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void standardSlotWithInvalidPhysicalIndexIsUnknown(int physical) {
        Slot target = new Slot(new SimpleContainer(1), physical, 0, 0);
        ServerPlayer player = playerFor(new TestMenu(target));

        assertEquals(physical, target.getContainerSlot());
        assertTrue(MenuActionAccess.validTarget(player, target));
        assertEquals(MenuActionAccess.Decision.UNKNOWN, check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
    }

    @Test
    void invalidStandardStorageDoesNotUseTheMenuValidityAsPermission() {
        SimpleContainer storage = new SimpleContainer(1) {
            @Override
            public boolean stillValid(Player player) {
                return false;
            }
        };
        Slot target = new Slot(storage, 0, 0, 0);
        ServerPlayer player = playerFor(new TestMenu(target));

        assertTrue(player.containerMenu.stillValid(player));
        assertFalse(storage.stillValid(player));
        assertEquals(MenuActionAccess.Decision.UNKNOWN, check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deny", "unknown", "null", "check_exception", "supports_exception"})
    void claimedResolverFailureNeverFallsBackToAnotherResolverOrStandardSlot(String outcome) {
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0);
        target.set(living(Items.LEVER, 1));
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);
        assertEquals(MenuActionAccess.Decision.ALLOW, check(player, target));
        RecordingResolver first = new RecordingResolver(menu, request -> switch (outcome) {
            case "deny" -> MenuActionAccess.Decision.DENY;
            case "unknown" -> MenuActionAccess.Decision.UNKNOWN;
            case "null" -> null;
            default -> throw new IllegalStateException("resolver failure");
        });
        first.failSupports = outcome.equals("supports_exception");
        RecordingResolver second = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(first);
        registerResolver(second);

        assertEquals(outcome.equals("deny") ? MenuActionAccess.Decision.DENY : MenuActionAccess.Decision.UNKNOWN,
            check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertEquals(2, first.supports.get());
        assertEquals(first.failSupports ? 0 : 2, first.checks.get());
        assertEquals(0, second.supports.get());
        assertEquals(0, second.checks.get());
        assertTrue(LivingItemManager.isLivingItem(target.getItem()));
        assertEquals(1, target.getItem().getCount());
    }

    @Test
    void unclaimedResolverDoesNotBlockStandardFallback() {
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0);
        target.set(living(Items.LEVER, 1));
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);
        RecordingResolver unrelated = new RecordingResolver(new TestMenu(), request -> MenuActionAccess.Decision.DENY);
        registerResolver(unrelated);

        assertEquals(MenuActionAccess.Decision.ALLOW, check(player, target));
        assertTrue(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertEquals(2, unrelated.supports.get());
        assertEquals(0, unrelated.checks.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "invalid_menu", "missing_menu", "detached_slot"})
    void invalidTargetIsRejectedBeforeResolvers(String condition) {
        SimpleContainer storage = new SimpleContainer(1);
        Slot target = condition.equals("inactive") ? new Slot(storage, 0, 0, 0) {
            @Override
            public boolean isActive() {
                return false;
            }
        } : new Slot(storage, 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);
        if (condition.equals("invalid_menu")) menu.valid = false;
        if (condition.equals("missing_menu")) player.containerMenu = null;
        if (condition.equals("detached_slot")) {
            target = new Slot(storage, 0, 0, 0);
            target.index = 0;
            assertFalse(menu.getSlot(0) == target);
        }
        RecordingResolver resolver = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(resolver);

        assertFalse(MenuActionAccess.validTarget(player, target));
        assertEquals(MenuActionAccess.Decision.DENY, MenuActionAccess.check(new MenuActionAccess.Request(
            player, menu, target, InteractionAction.Kind.ITEM_STATE, null, Set.of())));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertEquals(0, resolver.supports.get());
        assertTrue(storage.getItem(0).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2})
    void targetIndexMustAddressItsExactMenuSlot(int forgedIndex) {
        SimpleContainer storage = new SimpleContainer(2);
        Slot other = new Slot(storage, 0, 0, 0);
        Slot target = new Slot(storage, 1, 0, 0);
        TestMenu menu = new TestMenu(other, target);
        ServerPlayer player = playerFor(menu);
        RecordingResolver resolver = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(resolver);
        assertEquals(1, target.index);
        target.index = forgedIndex;

        assertSame(target, menu.slots.get(1));
        assertFalse(MenuActionAccess.validTarget(player, target));
        assertEquals(MenuActionAccess.Decision.DENY, check(player, target));
        assertFalse(InteractionAction.item(UNUSED_HANDLER).permits(player, target, ItemStack.EMPTY));
        assertEquals(0, resolver.supports.get());
    }

    @Test
    void requestRequiresCurrentMenuIdentityNotJustTheSameContainerId() {
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0);
        TestMenu current = new TestMenu(target);
        TestMenu stale = new TestMenu();
        ServerPlayer player = playerFor(current);
        RecordingResolver resolver = new RecordingResolver(stale, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(resolver);

        assertEquals(current.containerId, stale.containerId);
        assertTrue(MenuActionAccess.validTarget(player, target));
        assertEquals(MenuActionAccess.Decision.DENY, MenuActionAccess.check(new MenuActionAccess.Request(
            player, stale, target, InteractionAction.Kind.ITEM_STATE, null, Set.of())));
        assertEquals(0, resolver.supports.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no_context", "negative", "past_end", "missing_target"})
    void invalidScopeCannotReachAnAllowingResolver(String condition) {
        Slot target = new Slot(new SimpleContainer(13), 11, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = playerFor(menu);
        ContainerContext context = condition.equals("no_context") ? null : offsetContext("owner");
        Set<Integer> scope = switch (condition) {
            case "negative" -> Set.of(1, -1);
            case "past_end" -> Set.of(1, 3);
            case "missing_target" -> Set.of(2);
            default -> Set.of(1);
        };
        RecordingResolver resolver = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(resolver);
        InteractionAction action = new InteractionAction(InteractionAction.Kind.CONTAINER_STATE, UNUSED_HANDLER,
            (p, slot, carried) -> new InteractionAction.Scope(context, scope));

        assertEquals(11, target.getContainerSlot());
        assertEquals(MenuActionAccess.Decision.DENY, MenuActionAccess.check(new MenuActionAccess.Request(
            player, menu, target, action.kind(), context, scope)));
        assertFalse(action.permits(player, target, ItemStack.EMPTY));
        assertEquals(0, resolver.supports.get());
        verifyNoInteractions(player);
    }

    @Test
    void standardScopeUsesLogicalMappingAndActualOwnerRatherThanPhysicalNumberOrContents() {
        SimpleContainer storage = new SimpleContainer(13);
        Slot target = new Slot(storage, 11, 0, 0);
        Slot foreign = new Slot(new SimpleContainer(13), 12, 0, 0);
        Slot neighbor = new Slot(storage, 12, 0, 0);
        TestMenu menu = new TestMenu(foreign, target, neighbor);
        ServerPlayer player = playerFor(menu);
        TickableContainerContext context = offsetContext("owner");
        TickableContainerContext sameOwner = offsetContext("owner");
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(1, 2));

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, target)).thenReturn(sameOwner);
            owners.when(() -> ContainerContexts.resolve(player, neighbor)).thenReturn(sameOwner);
            owners.when(() -> ContainerContexts.resolve(player, foreign)).thenReturn(offsetContext("foreign"));
            assertEquals(1, target.index);
            assertEquals(11, target.getContainerSlot());
            assertTrue(foreign.getItem().isEmpty());
            assertTrue(neighbor.getItem().isEmpty());
            assertSame(target, MenuActionAccess.scopeSlot(request, 1));
            assertSame(neighbor, MenuActionAccess.scopeSlot(request, 2));
            assertEquals(MenuActionAccess.Decision.ALLOW, MenuActionAccess.check(request));
            assertTrue(new InteractionAction(request.kind(), UNUSED_HANDLER,
                (p, slot, carried) -> new InteractionAction.Scope(context, request.logicalSlots()))
                .permits(player, target, ItemStack.EMPTY));
        }
        verifyNoInteractions(player);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "duplicate", "inactive", "wrong_owner", "special"})
    void standardScopeCannotAuthorizeUnmappedAmbiguousInactiveOrSpecialSlots(String condition) {
        SimpleContainer storage = new SimpleContainer(13);
        Slot target = new Slot(storage, 11, 0, 0);
        Slot neighbor = switch (condition) {
            case "inactive" -> new Slot(storage, 12, 0, 0) {
                @Override
                public boolean isActive() {
                    return false;
                }
            };
            case "special" -> new Slot(storage, 12, 0, 0) {
                @Override
                public boolean mayPickup(Player player) {
                    return true;
                }
            };
            default -> new Slot(storage, 12, 0, 0);
        };
        TestMenu menu = new TestMenu(target);
        if (!condition.equals("absent")) menu.append(neighbor);
        Slot duplicate = new Slot(storage, 12, 0, 0);
        if (condition.equals("duplicate")) menu.append(duplicate);
        ServerPlayer player = playerFor(menu);
        TickableContainerContext context = offsetContext("owner");
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(1, 2));

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, target)).thenReturn(context);
            owners.when(() -> ContainerContexts.resolve(player, neighbor)).thenReturn(
                condition.equals("wrong_owner") ? offsetContext("foreign") : context);
            owners.when(() -> ContainerContexts.resolve(player, duplicate)).thenReturn(context);
            if (condition.equals("special")) assertSame(neighbor, MenuActionAccess.scopeSlot(request, 2));
            else assertNull(MenuActionAccess.scopeSlot(request, 2));
            assertEquals(condition.equals("special") ? MenuActionAccess.Decision.UNKNOWN
                : MenuActionAccess.Decision.DENY, MenuActionAccess.check(request));
            assertFalse(new InteractionAction(request.kind(), UNUSED_HANDLER,
                (p, slot, carried) -> new InteractionAction.Scope(context, request.logicalSlots()))
                .permits(player, target, ItemStack.EMPTY));
        }
        assertTrue(storage.getItem(11).isEmpty());
        assertTrue(storage.getItem(12).isEmpty());
        verifyNoInteractions(player);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_target_owner", "missing_target_owner", "absent_neighbor", "wrong_neighbor_owner", "duplicate_neighbor"})
    void explicitAllowCannotBypassRealScopeOwnershipAndCompleteMappings(String condition) {
        SimpleContainer storage = new SimpleContainer(13);
        Slot target = new SlotItemHandler(new ItemStackHandler(13), 11, 0, 0);
        Slot sameNumber = new Slot(storage, 11, 0, 0);
        Slot neighbor = new Slot(storage, 12, 0, 0);
        Slot duplicate = new Slot(storage, 12, 0, 0);
        TestMenu menu = new TestMenu(target, sameNumber);
        if (!condition.equals("absent_neighbor")) menu.append(neighbor);
        if (condition.equals("duplicate_neighbor")) menu.append(duplicate);
        ServerPlayer player = playerFor(menu);
        TickableContainerContext context = offsetContext("requested");
        TickableContainerContext foreign = offsetContext("foreign");
        RecordingResolver access = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        registerResolver(access);
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, menu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, Set.of(1, 2));

        try (MockedStatic<ContainerContexts> owners = mockStatic(ContainerContexts.class)) {
            owners.when(() -> ContainerContexts.resolve(player, target)).thenReturn(
                condition.equals("wrong_target_owner") ? foreign
                    : condition.equals("missing_target_owner") ? null : context);
            owners.when(() -> ContainerContexts.resolve(player, sameNumber)).thenReturn(
                condition.equals("wrong_target_owner") || condition.equals("missing_target_owner") ? context : foreign);
            owners.when(() -> ContainerContexts.resolve(player, neighbor)).thenReturn(
                condition.equals("wrong_neighbor_owner") ? foreign : context);
            owners.when(() -> ContainerContexts.resolve(player, duplicate)).thenReturn(context);
            assertEquals(target.getContainerSlot(), sameNumber.getContainerSlot());
            assertTrue(target.getItem().isEmpty());
            assertTrue(sameNumber.getItem().isEmpty());
            assertEquals(MenuActionAccess.Decision.DENY, MenuActionAccess.check(request));
            assertFalse(new InteractionAction(request.kind(), UNUSED_HANDLER,
                (p, slot, carried) -> new InteractionAction.Scope(context, request.logicalSlots()))
                .permits(player, target, ItemStack.EMPTY));
        }
        assertEquals(0, access.supports.get());
        assertEquals(0, access.checks.get());
        verifyNoInteractions(player);
    }

    static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static MenuActionAccess.Decision check(ServerPlayer player, Slot target) {
        return MenuActionAccess.check(new MenuActionAccess.Request(player, player.containerMenu, target,
            InteractionAction.Kind.ITEM_STATE, null, Set.of()));
    }

    static ServerPlayer playerFor(TestMenu menu) {
        ServerPlayer player = mock(ServerPlayer.class);
        player.containerMenu = menu;
        return player;
    }

    /** 无偏移的我方菜单上下文；不依赖真实世界或第三方实现。 */
    private static class IdentityContext extends FakeContainerContext implements TickableContainerContext {
        IdentityContext(String key) { super(9, 3, key); }
        @Override public void setTickContext(TickContext tick) {}
        @Override public void flushDirtySlots() {}
        @Override public List<BlockEntity> getAssociatedBlockEntities() { return List.of(); }
        @Override public Inventory getInventory() { return null; }
    }

    /** 我方逻辑槽接口夹具；物理索引前置十格，不访问世界注册表。 */
    static TickableContainerContext offsetContext(String key) {
        return new OffsetContext(key);
    }

    private static final class OffsetContext extends FakeContainerContext implements TickableContainerContext {
        OffsetContext(String key) {
            super(3, 3, key);
        }

        @Override
        public int toLogicalSlot(int physicalSlot) {
            return physicalSlot - 10;
        }

        @Override
        public int toPhysicalSlot(int logicalSlot) {
            return logicalSlot + 10;
        }

        @Override
        public void setTickContext(TickContext tick) {}

        @Override
        public void flushDirtySlots() {}

        @Override
        public List<BlockEntity> getAssociatedBlockEntities() {
            return List.of();
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** 真实菜单槽位列表；菜单类型与世界行为不参与授权测试。 */
    static final class TestMenu extends AbstractContainerMenu {
        boolean valid = true;

        TestMenu(Slot... slots) {
            super(null, 7);
            for (Slot slot : slots) addSlot(slot);
        }

        void append(Slot slot) {
            addSlot(slot);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return valid;
        }
    }

    /** 我方访问接口夹具；只认领指定菜单实例，记录是否发生后续查询。 */
    static final class RecordingResolver implements MenuActionAccess.Resolver {
        final AtomicInteger supports = new AtomicInteger();
        final AtomicInteger checks = new AtomicInteger();
        private final AbstractContainerMenu menu;
        private final Function<MenuActionAccess.Request, MenuActionAccess.Decision> decision;
        boolean failSupports;

        RecordingResolver(AbstractContainerMenu menu,
                          Function<MenuActionAccess.Request, MenuActionAccess.Decision> decision) {
            this.menu = menu;
            this.decision = decision;
        }

        @Override
        public boolean supports(AbstractContainerMenu candidate) {
            supports.incrementAndGet();
            if (failSupports) throw new IllegalStateException("supports failure");
            return candidate == menu;
        }

        @Override
        public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
            checks.incrementAndGet();
            return decision.apply(request);
        }
    }
}
