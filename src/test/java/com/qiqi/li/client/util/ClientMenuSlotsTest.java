package com.qiqi.li.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 纯映射接缝；lambda 提供已知显示槽映射，不验证客户端 Mixin 或屏幕解包。 */
class ClientMenuSlotsTest {
    @Test
    void ordinaryMenu_mapsExactDisplaySlotDespiteDuplicatePhysicalNumbers() {
        FakeMenu menu = new FakeMenu();
        Slot first = displaySlot(2);
        Slot second = displaySlot(2);
        menu.attach(first);
        menu.attach(second);
        Function<Slot, Slot> unused = display -> {
            throw new AssertionError("普通菜单无需创造槽解包");
        };

        assertEquals(0, ClientMenuSlots.serverMenuSlot(menu, first, false, null, unused));
        assertEquals(1, ClientMenuSlots.serverMenuSlot(menu, second, false, null, unused));
    }

    @Test
    void knownDisplayMapping_usesRealInventoryTargetNotDisplayPhysicalNumber() {
        InventoryMenu inventoryMenu = inventoryMenu();
        Slot target = inventoryMenu.getSlot(38);
        assertEquals(2, target.getContainerSlot());
        FakeMenu displayMenu = new FakeMenu();
        displayMenu.attach(displaySlot(2));
        Slot knownDisplay = displaySlot(38);
        displayMenu.attach(knownDisplay);
        Function<Slot, Slot> resolver = display -> display == knownDisplay ? target : null;

        int mapped = ClientMenuSlots.serverMenuSlot(displayMenu, knownDisplay, true, inventoryMenu, resolver);

        assertEquals(38, mapped);
        assertSame(target, inventoryMenu.getSlot(mapped));
        assertEquals(2, inventoryMenu.getSlot(mapped).getContainerSlot());
        assertEquals(38, knownDisplay.getContainerSlot());
    }

    @Test
    void unmappedCatalogAndDisposalSlots_areRejectedDespiteMatchingNumbers() {
        InventoryMenu inventoryMenu = inventoryMenu();
        FakeMenu displayMenu = new FakeMenu();
        Slot knownDisplay = displaySlot(38);
        Slot catalog = displaySlot(38);
        Slot disposal = displaySlot(2);
        displayMenu.attach(knownDisplay);
        displayMenu.attach(catalog);
        displayMenu.attach(disposal);
        Function<Slot, Slot> resolver = display -> display == knownDisplay ? inventoryMenu.getSlot(38) : null;

        assertEquals(38, ClientMenuSlots.serverMenuSlot(displayMenu, knownDisplay, true, inventoryMenu, resolver));
        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, catalog, true, inventoryMenu, resolver));
        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, disposal, true, inventoryMenu, resolver));
    }

    @Test
    void ordinaryDisplayCopy_isRejectedEvenWithSameContainerIndexAndCoordinates() {
        FakeMenu menu = new FakeMenu();
        SimpleContainer container = new SimpleContainer(9);
        Slot target = new Slot(container, 2, 18, 18);
        menu.attach(target);
        Slot copy = new Slot(container, 2, 18, 18);
        copy.index = target.index;

        assertEquals(-1, ClientMenuSlots.serverMenuSlot(menu, copy, false, null, display -> null));
    }

    @Test
    void knownMappingToInventorySlotCopy_isRejectedByTargetReference() {
        InventoryMenu inventoryMenu = inventoryMenu();
        Slot target = inventoryMenu.getSlot(38);
        Slot copy = new Slot(target.container, target.getContainerSlot(), target.x, target.y);
        copy.index = target.index;
        FakeMenu displayMenu = new FakeMenu();
        Slot knownDisplay = displaySlot(38);
        displayMenu.attach(knownDisplay);
        Function<Slot, Slot> resolver = display -> display == knownDisplay ? copy : null;

        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, knownDisplay, true, inventoryMenu, resolver));
    }

    @Test
    void mappedDisplayOutsideMenu_isRejectedBeforeResolver() {
        InventoryMenu inventoryMenu = inventoryMenu();
        FakeMenu displayMenu = new FakeMenu();
        Slot attached = displaySlot(38);
        displayMenu.attach(attached);
        Slot copy = displaySlot(38);
        copy.index = attached.index;
        Function<Slot, Slot> unused = display -> {
            throw new AssertionError("显示槽副本不应到达解包边界");
        };

        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, copy, true, inventoryMenu, unused));
    }

    @Test
    void inactiveDisplay_isRejectedBeforeResolverInBothModes() {
        FakeMenu displayMenu = new FakeMenu();
        Slot inactive = new Slot(new SimpleContainer(9), 2, 0, 0) {
            @Override public boolean isActive() { return false; }
        };
        displayMenu.attach(inactive);
        Function<Slot, Slot> unused = display -> {
            throw new AssertionError("非 active 显示槽不应到达解包边界");
        };

        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, inactive, false, null, unused));
        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, inactive, true, inventoryMenu(), unused));
    }

    @Test
    void inactiveMappedTarget_isRejectedEvenWhenItBelongsToServerMenu() {
        FakeMenu serverMenu = new FakeMenu();
        Slot target = new Slot(new SimpleContainer(9), 2, 0, 0) {
            @Override public boolean isActive() { return false; }
        };
        serverMenu.attach(target);
        FakeMenu displayMenu = new FakeMenu();
        Slot knownDisplay = displaySlot(0);
        displayMenu.attach(knownDisplay);
        Function<Slot, Slot> resolver = display -> display == knownDisplay ? target : null;

        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, knownDisplay, true, serverMenu, resolver));
    }

    @Test
    void missingMenuSlotInventoryOrMapping_isRejected() {
        InventoryMenu inventoryMenu = inventoryMenu();
        FakeMenu displayMenu = new FakeMenu();
        Slot display = displaySlot(38);
        displayMenu.attach(display);
        Function<Slot, Slot> unmapped = slot -> null;

        assertEquals(-1, ClientMenuSlots.serverMenuSlot(null, display, false, null, unmapped));
        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, null, false, null, unmapped));
        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, display, true, null, unmapped));
        assertEquals(-1, ClientMenuSlots.serverMenuSlot(displayMenu, display, true, inventoryMenu, unmapped));
    }

    private static InventoryMenu inventoryMenu() {
        Player player = mock(Player.class);
        Inventory inventory = new Inventory(player);
        return new InventoryMenu(inventory, false, player);
    }

    private static Slot displaySlot(int physical) {
        return new Slot(new SimpleContainer(46), physical, 0, 0);
    }

    private static class FakeMenu extends AbstractContainerMenu {
        FakeMenu() { super(null, 99); }
        void attach(Slot slot) { addSlot(slot); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
