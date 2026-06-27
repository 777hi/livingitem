package com.qiqi.li.client.mixin;

import com.qiqi.li.client.gui.LivingButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
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

/**
 * 容器界面 Mixin —— 在箱子界面中添加 {@link LivingButton}。
 *
 * 功能：
 * - 在 ContainerScreen（箱子界面）初始化时，在界面底部添加活物品切换按钮
 * - 当界面位置变化时（如窗口大小调整），重新定位按钮
 * - 只对 ContainerScreen 生效，不影响其他容器界面
 *
 * 按钮位置：界面底部中央（slot 9 上方）
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

    @Unique
    private int living_item$previousLeftPos = this.leftPos;

    @Unique
    private int living_item$previousTopPos = this.topPos;

    /**
     * 界面初始化时添加 LivingButton。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void living_item$addLivingButton(CallbackInfo ci) {
        this.living_item$initButtons();
        LivingButton.LOGGER.info("AbstractContainerScreenMixin on loaded");
    }

    /**
     * 每 tick 检查界面位置是否变化，如果变化则重新定位按钮。
     * 窗口大小调整等操作会导致 leftPos/topPos 改变。
     */
    @Inject(method = "containerTick", at = @At("TAIL"))
    private void living_item$checkForLeftOrTopPosChange(CallbackInfo ci) {
        if (this.leftPos != this.living_item$previousLeftPos || this.topPos != this.living_item$previousTopPos) {
            this.living_item$initButtons();
        }
    }

    /**
     * 初始化/重新定位 LivingButton。
     *
     * 逻辑：
     * 1. 只对 ContainerScreen（箱子界面）生效
     * 2. 移除已有的 LivingButton（避免重复添加）
     * 3. 在 slot 9 上方创建新的 LivingButton
     */
    @Unique
    private void living_item$initButtons() {
        if (!((Screen) this instanceof ContainerScreen)) {
            return;
        }

        List<LivingButton> existingLivingButtons = new ArrayList<>();
        for (GuiEventListener widget : this.children()) {
            if (widget instanceof LivingButton livingButton) {
                existingLivingButtons.add(livingButton);
            }
        }

        for (LivingButton button : existingLivingButtons) {
            this.removeWidget(button);
        }

        if (this.menu.slots.size() > 9) {
            Slot invSlot = this.menu.slots.get(9);
            this.addRenderableWidget(new LivingButton(
                    this.leftPos + 80, this.topPos + this.imageHeight - 94, this.menu, invSlot));
        }

        this.living_item$previousLeftPos = this.leftPos;
        this.living_item$previousTopPos = this.topPos;
    }
}