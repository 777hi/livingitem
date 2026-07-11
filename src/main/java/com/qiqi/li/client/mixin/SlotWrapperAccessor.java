package com.qiqi.li.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * SlotWrapper的访问器，用于获取被包装的原始槽位（target字段）。
 *
 * 创造模式INVENTORY标签页中，快捷栏槽位被SlotWrapper包装。
 * SlotWrapper.getContainerSlot()返回的是inventoryMenu菜单索引（如38），
 * 而target.getContainerSlot()才返回真实的容器槽位索引（如2）。
 * 服务端需要真实容器索引才能正确定位活TNT物品。
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public interface SlotWrapperAccessor {
    @Accessor("target")
    Slot getTarget();
}