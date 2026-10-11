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
 *
 * <p>⚠️ <b>重命名原因</b>：inventoryessentials（背包必备，blay09）也声明了同名
 * {@code @Accessor getTarget()}，命中同一目标类
 * {@code CreativeModeInventoryScreen$SlotWrapper}，导致 Mixin 报
 * "Incompatible @Accessor getTarget ... previously written by ..." 冲突，
 * 打开创造模式标签页加载 Slot 时 {@code MixinApplyError} 崩溃。
 * 故本访问器方法名加 {@code living_item$} 前缀，使 Mixin 生成的合成方法名全局唯一，
 * 不再与对方（或任何其它模组）碰撞。目标类与功能不变。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public interface LivingItemSlotWrapperAccessor {
    @Accessor("target")
    Slot living_item$getWrappedTarget();
}
