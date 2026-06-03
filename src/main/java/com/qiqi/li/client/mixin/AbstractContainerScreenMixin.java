package com.qiqi.li.client.mixin;

import com.qiqi.li.client.gui.LivingButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

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

    @Unique
    private int living_item$previousLeftPos = this.leftPos;

    @Unique
    private int living_item$previousTopPos = this.topPos;

    @Inject(method = "init",at = @At("TAIL"))
    private void living_item$addLivingButton(CallbackInfo ci){
        this.living_item$initButtons();
        LivingButton.LOGGER.info("AbstractContainerScreenMixin on loaded");
    }

    @Inject(method = "containerTick",at = @At("TAIL"))
    private void living_item$checkForLeftOrTopPosChange(CallbackInfo ci){
        if(this.leftPos != this.living_item$previousLeftPos || this.topPos != this.living_item$previousTopPos){
            this.living_item$initButtons();
        }
    }

    @Unique
    private void living_item$initButtons(){
        if(!((Screen) this instanceof ContainerScreen)){
            return;
        }

        List<LivingButton> existingLivingButtons = new ArrayList<>();
        for (GuiEventListener widget : this.children()) {
            if (widget instanceof LivingButton sortButton) {
                existingLivingButtons.add(sortButton);
            }
        }

        for (LivingButton button : existingLivingButtons){
            this.removeWidget(button);
        }

//        Slot invSlot = null;

//        for (Slot slot : this.menu.slots) {
//            if (slot.container instanceof Inventory) {
//                invSlot = slot;
//                break;
//            }
//        }

        if (this.menu.slots.size() > 9) {
            Slot invSlot = this.menu.slots.get(9);
            this.addRenderableWidget(new LivingButton(this.leftPos + 80, this.topPos + this.imageHeight - 94, this.menu, invSlot));
        }

        this.living_item$previousLeftPos = this.leftPos;
        this.living_item$previousTopPos = this.topPos;
    }
}
