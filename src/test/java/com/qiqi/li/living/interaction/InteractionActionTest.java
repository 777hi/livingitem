package com.qiqi.li.living.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.interaction.MenuActionAccessTest.RecordingResolver;
import com.qiqi.li.living.interaction.MenuActionAccessTest.TestMenu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** 动作授权范围；权限查询不执行处理器、不修改光标或真实存储。 */
class InteractionActionTest {
    @BeforeEach
    void setUp() {
        MenuActionAccess.clearForTest();
        InteractionRegistry.clearForTest();
    }

    @AfterEach
    void tearDown() {
        MenuActionAccess.clearForTest();
        InteractionRegistry.clearForTest();
    }

    @Test
    void itemFactoryUsesOnlyTheExactTargetWithoutResolvingAContainer() {
        SimpleContainer storage = new SimpleContainer(1);
        Slot target = new Slot(storage, 0, 0, 0);
        ServerPlayer player = MenuActionAccessTest.playerFor(new TestMenu(target));
        InteractionHandler handler = (p, slot) -> { throw new AssertionError("permission query executed handler"); };
        InteractionAction action = InteractionAction.item(handler);
        ItemStack carried = new ItemStack(Items.BUCKET, 4);
        ItemStack contents = MenuActionAccessTest.living(Items.LEVER, 1);
        storage.setItem(0, contents);

        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            InteractionAction.Scope scope = action.scopeResolver().resolve(player, target, carried);
            assertEquals(InteractionAction.Kind.ITEM_STATE, action.kind());
            assertSame(handler, action.handler());
            assertNull(scope.context());
            assertTrue(scope.logicalSlots().isEmpty());
            assertTrue(action.permits(player, target, carried));
            contexts.verifyNoInteractions();
        }
        assertSame(contents, storage.getItem(0));
        assertEquals(4, carried.getCount());
        verifyNoInteractions(player);
    }

    @Test
    void containerFactoryUsesResolvedContextAndLogicalTargetIndex() {
        ItemStackHandler storage = new ItemStackHandler(13);
        Slot target = new SlotItemHandler(storage, 11, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        TickableContainerContext context = MenuActionAccessTest.offsetContext("owner");
        AtomicInteger executions = new AtomicInteger();
        InteractionHandler handler = (p, slot) -> executions.incrementAndGet();
        InteractionAction action = InteractionAction.container(handler);
        ItemStack carried = new ItemStack(Items.BUCKET, 3);
        RecordingResolver access = new RecordingResolver(menu, request -> {
            assertEquals(InteractionAction.Kind.CONTAINER_STATE, request.kind());
            assertSame(context, request.context());
            assertSame(target, request.target());
            assertEquals(Set.of(1), request.logicalSlots());
            return MenuActionAccess.Decision.ALLOW;
        });
        MenuActionAccess.register(access);

        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            contexts.when(() -> ContainerContexts.resolve(player, target)).thenReturn(context);
            InteractionAction.Scope scope = action.scopeResolver().resolve(player, target, carried);
            assertSame(handler, action.handler());
            assertSame(context, scope.context());
            assertEquals(0, target.index);
            assertEquals(11, target.getContainerSlot());
            assertEquals(Set.of(1), scope.logicalSlots());
            assertFalse(target.mayPickup(player));
            assertTrue(action.permits(player, target, carried));
        }
        assertEquals(1, access.checks.get());
        assertEquals(0, executions.get());
        assertTrue(storage.getStackInSlot(11).isEmpty());
        assertEquals(3, carried.getCount());
        verifyNoInteractions(player);
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 13})
    void containerFactoryRejectsOutOfRangeLogicalTargetsBeforeAccessResolvers(int physical) {
        Slot target = new Slot(new SimpleContainer(14), physical, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        TickableContainerContext context = MenuActionAccessTest.offsetContext("owner");
        RecordingResolver access = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        MenuActionAccess.register(access);
        InteractionAction action = InteractionAction.container((p, slot) -> {
            throw new AssertionError("permission query executed handler");
        });

        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            contexts.when(() -> ContainerContexts.resolve(player, target)).thenReturn(context);
            assertFalse(context.isValidSlot(context.toLogicalSlot(physical)));
            assertNull(action.scopeResolver().resolve(player, target, ItemStack.EMPTY));
            assertFalse(action.permits(player, target, ItemStack.EMPTY));
        }
        assertEquals(0, access.supports.get());
        assertTrue(target.getItem().isEmpty());
        verifyNoInteractions(player);
    }

    @Test
    void unresolvedContainerDoesNotFallBackToAnItemActionOrResolverAllowance() {
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        RecordingResolver access = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        MenuActionAccess.register(access);
        InteractionAction action = InteractionAction.container((p, slot) -> {});

        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            contexts.when(() -> ContainerContexts.resolve(player, target)).thenReturn(null);
            assertNull(action.scopeResolver().resolve(player, target, ItemStack.EMPTY));
            assertFalse(action.permits(player, target, ItemStack.EMPTY));
        }
        assertEquals(0, access.supports.get());
        verifyNoInteractions(player);
    }

    @Test
    void invalidTargetStopsBeforeTheScopeResolverAndHandler() {
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        menu.valid = false;
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        AtomicInteger scopes = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        InteractionAction action = new InteractionAction(InteractionAction.Kind.USE_CONTAINER,
            (p, slot) -> executions.incrementAndGet(), (p, slot, carried) -> {
                scopes.incrementAndGet();
                return new InteractionAction.Scope(null, Set.of());
            });

        assertFalse(action.permits(player, target, ItemStack.EMPTY));
        assertFalse(action.permits(player, null, ItemStack.EMPTY));
        assertEquals(0, scopes.get());
        assertEquals(0, executions.get());
        assertTrue(target.getItem().isEmpty());
    }

    @Test
    void nullCandidateScopeRejectsWithoutExecutingOrCallingAccessResolvers() {
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        ItemStack carried = new ItemStack(Items.BUCKET, 2);
        AtomicInteger scopes = new AtomicInteger();
        RecordingResolver access = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        MenuActionAccess.register(access);
        InteractionAction action = new InteractionAction(InteractionAction.Kind.USE_CONTAINER,
            (p, slot) -> { throw new AssertionError("permission query executed handler"); },
            (p, slot, cursor) -> {
                assertSame(player, p);
                assertSame(target, slot);
                assertSame(carried, cursor);
                scopes.incrementAndGet();
                return null;
            });

        assertFalse(action.permits(player, target, carried));
        assertEquals(1, scopes.get());
        assertEquals(0, access.supports.get());
        assertEquals(2, carried.getCount());
        assertTrue(target.getItem().isEmpty());
    }

    @Test
    void matchingItemsAndTriggerFilterCannotAuthorizeAnUnadaptedSlot() {
        ItemStackHandler storage = new ItemStackHandler(1);
        ItemStack contents = MenuActionAccessTest.living(Items.LEVER, 1);
        storage.setStackInSlot(0, contents);
        Slot target = new SlotItemHandler(storage, 0, 0, 0);
        ServerPlayer player = MenuActionAccessTest.playerFor(new TestMenu(target));
        ItemStack carried = new ItemStack(Items.STICK, 2);
        InteractionEntry entry = new InteractionEntry(Items.LEVER, Items.STICK, 1, "state_test",
            false, (cursor, stack) -> cursor.getCount() >= stack.getCount());
        InteractionAction action = InteractionAction.item((p, slot) -> {
            throw new AssertionError("permission query executed handler");
        });

        assertTrue(entry.matchesTarget(contents));
        assertTrue(entry.matchesTrigger(carried));
        assertTrue(entry.triggerFilter().test(carried, contents));
        assertTrue(target.mayPickup(player));
        assertFalse(action.permits(player, target, carried));
        assertSame(contents, storage.getStackInSlot(0));
        assertEquals(1, contents.getCount());
        assertEquals(2, carried.getCount());
    }

    @Test
    void scopeAndRequestSnapshotTheirLogicalSlots() {
        Set<Integer> mutable = new HashSet<>(Set.of(1));
        TickableContainerContext context = MenuActionAccessTest.offsetContext("owner");
        Slot target = new Slot(new SimpleContainer(13), 11, 0, 0);
        ServerPlayer player = MenuActionAccessTest.playerFor(new TestMenu(target));
        InteractionAction.Scope scope = new InteractionAction.Scope(context, mutable);
        MenuActionAccess.Request request = new MenuActionAccess.Request(player, player.containerMenu, target,
            InteractionAction.Kind.CONTAINER_STATE, context, mutable);

        mutable.clear();
        mutable.add(2);
        assertEquals(Set.of(1), scope.logicalSlots());
        assertEquals(Set.of(1), request.logicalSlots());
        assertThrows(UnsupportedOperationException.class, () -> scope.logicalSlots().add(2));
        assertThrows(UnsupportedOperationException.class, () -> request.logicalSlots().add(2));
    }

    @Test
    void itemActionRequiresNonemptyLivingTargetEvenWithExplicitAllowance() {
        SimpleContainer storage = new SimpleContainer(1);
        Slot target = new Slot(storage, 0, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        RecordingResolver access = new RecordingResolver(menu, request -> MenuActionAccess.Decision.ALLOW);
        MenuActionAccess.register(access);
        InteractionAction action = InteractionAction.item((p, slot) -> {
            throw new AssertionError("permission query executed handler");
        });

        assertFalse(action.permits(player, target, ItemStack.EMPTY));
        storage.setItem(0, new ItemStack(Items.LEVER));
        assertFalse(action.permits(player, target, ItemStack.EMPTY));
        assertEquals(0, access.supports.get());
        ItemStack contents = MenuActionAccessTest.living(Items.LEVER, 1);
        storage.setItem(0, contents);
        assertTrue(action.permits(player, target, ItemStack.EMPTY));
        assertEquals(1, access.checks.get());
        assertSame(contents, storage.getItem(0));
        verifyNoInteractions(player);
    }

    @Test
    void registryWrappersKeepLegacyItemAndExplicitContainerAuthorization() {
        AtomicInteger legacyExecutions = new AtomicInteger();
        AtomicInteger containerExecutions = new AtomicInteger();
        InteractionHandler legacy = (player, target) -> legacyExecutions.incrementAndGet();
        InteractionAction explicit = InteractionAction.container((player, target) -> containerExecutions.incrementAndGet());
        InteractionRegistry.registerHandler("test_legacy", legacy);
        InteractionRegistry.registerHandler("test_container", explicit);
        SimpleContainer storage = new SimpleContainer(13);
        Slot target = new Slot(storage, 11, 0, 0);
        TestMenu menu = new TestMenu(target);
        ServerPlayer player = MenuActionAccessTest.playerFor(menu);
        TickableContainerContext context = MenuActionAccessTest.offsetContext("owner");
        ItemStack carried = new ItemStack(Items.BUCKET, 2);
        menu.setCarried(carried);
        InteractionHandler legacyWrapper = InteractionRegistry.getHandler("test_legacy");
        InteractionHandler containerWrapper = InteractionRegistry.getHandler("test_container");

        assertFalse(legacy == legacyWrapper);
        assertSame(legacy, InteractionRegistry.getAction("test_legacy").handler());
        assertEquals(InteractionAction.Kind.ITEM_STATE, InteractionRegistry.getAction("test_legacy").kind());
        assertSame(explicit, InteractionRegistry.getAction("test_container"));
        assertFalse(explicit.handler() == containerWrapper);
        assertEquals(InteractionAction.Kind.CONTAINER_STATE, explicit.kind());
        try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class)) {
            contexts.when(() -> ContainerContexts.resolve(player, target)).thenReturn(context);
            legacyWrapper.handle(player, target);
            assertEquals(0, legacyExecutions.get());
            containerWrapper.handle(player, target);
            assertEquals(1, containerExecutions.get());
            ItemStack contents = MenuActionAccessTest.living(Items.LEVER, 1);
            storage.setItem(11, contents);
            legacyWrapper.handle(player, target);
            assertEquals(1, legacyExecutions.get());
            MenuActionAccess.register(new RecordingResolver(menu, request -> MenuActionAccess.Decision.DENY));
            legacyWrapper.handle(player, target);
            containerWrapper.handle(player, target);
            assertEquals(1, legacyExecutions.get());
            assertEquals(1, containerExecutions.get());
            assertSame(contents, storage.getItem(11));
        }
        assertSame(carried, menu.getCarried());
        assertEquals(2, carried.getCount());
        verifyNoInteractions(player);
    }
}
