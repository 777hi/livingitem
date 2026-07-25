package com.qiqi.li.client.mixin;

import com.qiqi.li.client.GuiInteractionHelper;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;


import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用容器界面Mixin，处理活物品GUI交互和LivingButton渲染。
 *
 * 交互拦截：
 *   通过 GuiInteractionHelper.tryInteract() 统一检测活物品交互，
 *   匹配到交互规则时取消原版点击行为并发送网络包。
 *   不再硬编码具体的物品类型判断。
 *
 * LivingButton：
 *   在容器/潜影盒界面中添加活物品切换按钮。
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin extends Screen {
    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow @Final
    protected AbstractContainerMenu menu;

    @Shadow protected int imageHeight;

    @Shadow protected int imageWidth;

    @Shadow
    protected Slot hoveredSlot;

    @Unique
    private int living_item$previousLeftPos = this.leftPos;

    @Unique
    private int living_item$previousTopPos = this.topPos;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void living_item$interceptMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, this.menu)) {
            cir.setReturnValue(true);
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            boolean hasLivingChest = false;
            if (this.hoveredSlot != null) {
                ItemStack slotStack = this.hoveredSlot.getItem();
                if (LivingChestFunction.isLivingChest(slotStack)) {
                    hasLivingChest = true;
                }
            }
            if (!hasLivingChest) {
                ItemStack cursorStack = this.menu.getCarried();
                if (LivingChestFunction.isLivingChest(cursorStack)) {
                    hasLivingChest = true;
                }
            }
            if (hasLivingChest) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_1 && hasShiftDown()
            && LivingChestTabState.isActive()
            && living_item$isRecipeBookVisible()
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
        if (GuiInteractionHelper.tryInteract(this.hoveredSlot, button, this.menu)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void living_item$addLivingButton(CallbackInfo ci) {
        this.living_item$initButtons();
        LivingButton.LOGGER.info("AbstractContainerScreenMixin on loaded");
    }

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void living_item$checkForLeftOrTopPosChange(CallbackInfo ci) {
        if (this.leftPos != this.living_item$previousLeftPos || this.topPos != this.living_item$previousTopPos) {
            this.living_item$initButtons();
        }
    }

    @Unique
    private void living_item$initButtons() {
        List<LivingButton> existingLivingButtons = new ArrayList<>();
        for (GuiEventListener widget : this.children()) {
            if (widget instanceof LivingButton livingButton) {
                existingLivingButtons.add(livingButton);
            }
        }

        for (LivingButton button : existingLivingButtons) {
            this.removeWidget(button);
        }

        if ((Screen) this instanceof InventoryScreen || (Screen) this instanceof CreativeModeInventoryScreen) {
            this.living_item$previousLeftPos = this.leftPos;
            this.living_item$previousTopPos = this.topPos;
            return;
        }

        if (this.menu.slots.size() > 9) {
            Slot invSlot = this.menu.slots.get(9);
            this.addRenderableWidget(new LivingButton(
                    this.leftPos + (this.imageWidth - 16) / 2, this.topPos + this.imageHeight - 94, this.menu, invSlot));
        }

        this.living_item$previousLeftPos = this.leftPos;
        this.living_item$previousTopPos = this.topPos;
    }

    @Unique
    private boolean living_item$isRecipeBookVisible() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof InventoryScreen invScreen) {
            return invScreen.getRecipeBookComponent().isVisible();
        }
        return false;
    }
}