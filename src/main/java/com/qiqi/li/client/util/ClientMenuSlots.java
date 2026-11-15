package com.qiqi.li.client.util;

import com.qiqi.li.client.mixin.LivingItemSlotWrapperAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;
import java.util.function.Function;

/** 客户端显示槽到服务端真实菜单槽的映射；目录、销毁格与槽位副本不参与。 */
public final class ClientMenuSlots {
    private ClientMenuSlots() {}

    /** 存储玩法的额外准入；一般物品交互只需真实菜单槽。 */
    public static boolean isStorageSlot(AbstractContainerMenu menu, Slot slot) {
        if (serverMenuSlot(menu, slot) < 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CreativeModeInventoryScreen) {
            Slot target = ((LivingItemSlotWrapperAccessor) slot).living_item$getWrappedTarget();
            if (mc.player == null || target.container != mc.player.getInventory()) return false;
        }
        return ClientMenuSlotOwners.resolveStorageSlot(menu, slot) >= 0;
    }

    public static int serverMenuSlot(AbstractContainerMenu menu, Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        return serverMenuSlot(menu, slot, mc.screen instanceof CreativeModeInventoryScreen,
            mc.player == null ? null : mc.player.inventoryMenu);
    }

    /** 客户端桥：只有真实创造 wrapper 可提供被包装的目标槽。 */
    public static int serverMenuSlot(@Nullable AbstractContainerMenu menu, @Nullable Slot slot,
                                     boolean creative, @Nullable AbstractContainerMenu inventoryMenu) {
        return serverMenuSlot(menu, slot, creative, inventoryMenu,
            display -> display instanceof LivingItemSlotWrapperAccessor wrapper
                ? wrapper.living_item$getWrappedTarget() : null);
    }

    /** 纯映射接缝；解析器只认已知 wrapper，目录/销毁格返回 null，身份仍按引用核验。 */
    public static int serverMenuSlot(@Nullable AbstractContainerMenu menu, @Nullable Slot slot,
                                     boolean creative, @Nullable AbstractContainerMenu inventoryMenu,
                                     Function<Slot, Slot> wrappedTargetResolver) {
        if (menu == null || slot == null || !slot.isActive()) return -1;
        int displayIndex = exactSlotIndex(menu, slot);
        if (displayIndex < 0) return -1;
        if (!creative) return displayIndex;
        if (inventoryMenu == null) return -1;
        Slot target = wrappedTargetResolver.apply(slot);
        return target == null || !target.isActive() ? -1 : exactSlotIndex(inventoryMenu, target);
    }

    /** 对应服务端菜单槽的物理槽号；不穿透普通菜单槽另猜库存。 */
    public static int serverContainerSlot(AbstractContainerMenu menu, Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        boolean creative = mc.screen instanceof CreativeModeInventoryScreen;
        AbstractContainerMenu inventoryMenu = mc.player == null ? null : mc.player.inventoryMenu;
        int index = serverMenuSlot(menu, slot, creative, inventoryMenu);
        if (index < 0) return -1;
        return (creative ? inventoryMenu : menu).slots.get(index).getContainerSlot();
    }

    public static int serverMenuId(AbstractContainerMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CreativeModeInventoryScreen) {
            return mc.player == null ? -1 : mc.player.inventoryMenu.containerId;
        }
        return menu == null ? -1 : menu.containerId;
    }

    private static int exactSlotIndex(AbstractContainerMenu menu, Slot slot) {
        int index = slot.index;
        if (index >= 0 && index < menu.slots.size() && menu.slots.get(index) == slot) return index;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) == slot) return i;
        }
        return -1;
    }
}
