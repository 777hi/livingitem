package com.qiqi.li.client.mixin;

import com.qiqi.li.client.GuiInteractionHelper;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 创造模式背包界面的Mixin，拦截活物品GUI交互。
 *
 * SlotWrapper 兼容：
 *   创造模式 INVENTORY 标签页中，快捷栏槽位被 SlotWrapper 包装，
 *   getContainerSlot() 返回的是 inventoryMenu 菜单索引而非真实容器索引。
 *   GuiInteractionHelper.tryInteract() 内部通过 SlotWrapperAccessor
 *   统一处理此差异，此 Mixin 无需额外关注。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin extends EffectRenderingInventoryScreen<CreativeModeInventoryScreen.ItemPickerMenu> {
    public CreativeModeInventoryScreenMixin(CreativeModeInventoryScreen.ItemPickerMenu menu, net.minecraft.world.entity.player.Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, this.menu)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, this.menu)) {
            cir.setReturnValue(true);
        }
    }
}