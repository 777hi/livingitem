package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.domain.portal.PortalInteractHandlers;
import com.qiqi.li.living.interaction.InteractionAction;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.MenuActionAccess;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** 真实服务端分发入口；测试动作不触发世界操作，门面维护在静态边界隔离。 */
class GuiInteractionDispatchTest {
    private static final String ACTION = "test_dispatch_item";
    private static final int PHYSICAL = 4;

    private List<InteractionEntry> savedEntries;
    private Map<String, InteractionAction> savedActions;
    private List<MenuActionAccess.Resolver> savedResolvers;
    private MockedStatic<PortalInteractHandlers> portal;
    private ServerPlayer player;
    private FakeMenu menu;
    private Slot target;
    private ItemStack original;
    private InteractionHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        savedEntries = InteractionRegistry.getEntries();
        savedActions = new HashMap<>(registeredActions());
        savedResolvers = new ArrayList<>(registeredResolvers());
        clear(InteractionRegistry.class);
        clear(MenuActionAccess.class);
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
            clear(InteractionRegistry.class);
            clear(MenuActionAccess.class);
            savedEntries.forEach(InteractionRegistry::register);
            savedActions.forEach(InteractionRegistry::registerHandler);
            savedResolvers.forEach(MenuActionAccess::register);
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
        InteractionRegistry.register(new InteractionEntry(Items.STONE_BUTTON, null, 1, ACTION));
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
        MenuActionAccess.register(new MenuActionAccess.Resolver() {
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

    private MenuActionAccess.Resolver registerAccess(MenuActionAccess.Decision decision) {
        MenuActionAccess.Resolver resolver = mock(MenuActionAccess.Resolver.class);
        when(resolver.supports(player.containerMenu)).thenReturn(true);
        when(resolver.check(isA(MenuActionAccess.Request.class))).thenReturn(decision);
        MenuActionAccess.register(resolver);
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
        ItemStack stack = new ItemStack(Items.LEVER);
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

    private static void clear(Class<?> type) throws Exception {
        Method method = type.getDeclaredMethod("clearForTest");
        method.setAccessible(true);
        method.invoke(null);
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
