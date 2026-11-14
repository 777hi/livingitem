package com.qiqi.li.client.util;

import com.qiqi.li.client.mixin.LivingItemSlotWrapperAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** 门面仅接入真实菜单槽，创造物品目录与销毁格不参与。 */
public final class PortalMenuSlots {
    private PortalMenuSlots() {}

    public static boolean isStorageSlot(AbstractContainerMenu menu, Slot slot) {
        if (menu == null || slot == null || !slot.isActive()) return false;
        if (Minecraft.getInstance().screen instanceof CreativeModeInventoryScreen) {
            if (!(slot instanceof LivingItemSlotWrapperAccessor wrapper)) return false;
            Slot target = wrapper.living_item$getWrappedTarget();
            var player = Minecraft.getInstance().player;
            return player != null && target.container == player.getInventory()
                && player.inventoryMenu.slots.contains(target);
        }
        return ClientMenuSlotOwners.resolveStorageSlot(menu, slot) >= 0;
    }

    public static int serverMenuSlot(AbstractContainerMenu menu, Slot slot) {
        if (slot instanceof LivingItemSlotWrapperAccessor wrapper) {
            return wrapper.living_item$getWrappedTarget().index;
        }
        return menu.slots.indexOf(slot);
    }

    public static int serverMenuId(AbstractContainerMenu menu) {
        var player = Minecraft.getInstance().player;
        return Minecraft.getInstance().screen instanceof CreativeModeInventoryScreen && player != null
            ? player.inventoryMenu.containerId : menu.containerId;
    }
}
