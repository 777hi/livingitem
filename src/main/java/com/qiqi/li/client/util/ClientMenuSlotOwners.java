package com.qiqi.li.client.util;

import com.qiqi.li.living.container.EntityContainers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** 客户端运行时/流体共用的存储槽归属；不保存菜单外的宿主状态。 */
public final class ClientMenuSlotOwners {
    private ClientMenuSlotOwners() {}

    /** 返回存储物理槽；实体菜单的装备、饰品、机器与隐藏格返回 -1。 */
    public static int resolveStorageSlot(AbstractContainerMenu menu, Slot slot) {
        if (menu == null || slot == null) return -1;
        if (slot.container instanceof Inventory) return ClientSlotResolve.resolveContainerSlot(slot);
        Entity entity = EntityContainers.menuHostEntity(menu, slot);
        return entity == null ? ClientSlotResolve.resolveContainerSlot(slot)
            : EntityContainers.menuPhysicalSlot(entity, menu, slot);
    }
}
