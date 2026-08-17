package com.qiqi.li.client.mixin;

import com.qiqi.li.client.input.GuiInteractionHelper;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生存模式背包界面Mixin，处理活物品GUI交互。
 *
 * 交互拦截：
 *   通过 GuiInteractionHelper.tryInteract() 统一检测活物品交互，
 *   匹配到交互规则时取消原版点击行为并发送网络包。
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends EffectRenderingInventoryScreen<InventoryMenu> {
    public InventoryScreenMixin(InventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Shadow
    private RecipeBookComponent recipeBookComponent;

    @Inject(method = "containerTick", at = @At("HEAD"))
    private void living_item$callContainerTick(CallbackInfo ci) {
        super.containerTick();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, false, this.menu)) {
            cir.setReturnValue(true);
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_1 && hasShiftDown()
            && LivingChestTabState.isActive()
            && this.recipeBookComponent.isVisible()
            && this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            ItemStack slotStack = this.hoveredSlot.getItem();
            if (!LivingChestFunction.isLivingChest(slotStack)) {
                CompoundTag itemTag = (CompoundTag) slotStack.saveOptional(
                    Minecraft.getInstance().player.registryAccess());
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT_SLOT, itemTag, slotStack.getCount()));
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, true, this.menu)) {
            cir.setReturnValue(true);
        }
    }
}