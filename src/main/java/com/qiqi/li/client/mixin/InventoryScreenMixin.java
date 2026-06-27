package com.qiqi.li.client.mixin;

import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家背包界面 Mixin —— 修复 Mojang 的 bug。
 *
 * 问题：InventoryScreen.containerTick() 没有调用 super.containerTick()，
 * 导致 {@link AbstractContainerScreenMixin} 中注入的 containerTick 逻辑不会执行，
 * LivingButton 不会在背包界面中更新位置。
 *
 * 修复：在 containerTick() 开头注入 super.containerTick() 调用。
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends EffectRenderingInventoryScreen<InventoryMenu> {
    public InventoryScreenMixin(InventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    /**
     * Mojang 的 InventoryScreen 没有调用 super.containerTick()，
     * 导致 AbstractContainerScreen.containerTick() 中的按钮位置更新逻辑不执行。
     * 在 HEAD 注入 super 调用来修复此问题。
     */
    @Inject(method = "containerTick", at = @At("HEAD"))
    private void living_item$callContainerTick(CallbackInfo ci) {
        super.containerTick();
    }
}