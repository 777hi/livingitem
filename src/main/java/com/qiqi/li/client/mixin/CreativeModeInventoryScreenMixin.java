package com.qiqi.li.client.mixin;

import com.qiqi.li.client.input.GuiInteractionHelper;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 创造模式背包界面的Mixin，拦截活物品GUI交互。
 *
 * SlotWrapper 兼容：
 *   创造模式 INVENTORY 标签页中，快捷栏槽位被 SlotWrapper 包装，
 *   getContainerSlot() 返回的是 inventoryMenu 菜单索引而非真实容器索引。
 *   GuiInteractionHelper.tryInteract() 内部通过 SlotWrapperAccessor
 *   统一处理此差异，此 Mixin 无需额外关注。
 *
 * 创造模式 INVENTORY 标签页同步修复：
 *   原版 slotClicked 在 INVENTORY 标签页的通用点击分支（PICKUP/SWAP/QUICK_MOVE）中，
 *   只调用了 inventoryMenu.clicked() 修改客户端本地状态，
 *   没有调用 handleCreativeModeItemAdd 将变更同步到服务端。
 *   导致活漏洞输出槽位的物品在客户端被"拿走"后，服务端实际未更新，
 *   活漏斗因目标槽位依然有物品而阻塞。
 *   此 Mixin 在 inventoryMenu.broadcastChanges() 后补发
 *   ServerboundSetCreativeModeSlotPacket，确保服务端同步。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin extends EffectRenderingInventoryScreen<CreativeModeInventoryScreen.ItemPickerMenu> {
    public CreativeModeInventoryScreenMixin(CreativeModeInventoryScreen.ItemPickerMenu menu, net.minecraft.world.entity.player.Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, false, this.menu)) {
            ((AbstractContainerScreenAccessor) this).setSkipNextRelease(true);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, true, this.menu)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 在 slotClicked 返回后，对 INVENTORY 标签页的通用点击补发同步包。
     *
     * 使用 @At("RETURN") 而非 @At.INVOKE 目标，因为后者在无 refmap
     * 的 dev 环境中可能解析失败(No refMap loaded)。
     * 通过 slot instanceof SlotWrapperAccessor 判断是否在 INVENTORY 标签页；
     * 排除 THROW 类型(原版已调用 handleCreativeModeItemAdd)。
     */
    @Inject(method = "slotClicked", at = @At("RETURN"))
    private void living_item$syncCreativeInventorySlot(Slot slot, int slotId, int mouseButton, ClickType type, CallbackInfo ci) {
        if (slot == null) return;
        if (type == ClickType.THROW) return;
        if (!(slot instanceof SlotWrapperAccessor)) return;

        SlotWrapperAccessor wrapper = (SlotWrapperAccessor) slot;
        int index = wrapper.getTarget().index;
        ItemStack item = slot.getItem();
        this.minecraft.gameMode.handleCreativeModeItemAdd(item, index);
    }
}