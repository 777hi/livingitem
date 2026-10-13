package com.qiqi.li.client.mixin;

import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.client.gui.LivingButtonLayout;
import com.qiqi.li.client.input.GuiInteractionHelper;
import com.qiqi.li.client.input.ToolRayTuningClicks;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
import org.spongepowered.asm.mixin.Unique;
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
            ((AbstractContainerScreenAccessor) this).living_item$setSkipNextRelease(true);
            cir.setReturnValue(true);
        }

        // ⭐ 射线微调：点击玩家小人（左键=起点锚点/脑袋重置，右键=朝向跟随切换）。
        //    小人区域没有 slot，tryInteract 不会命中，二者互不干扰；
        //    命中分区与光标/主手校验见 ToolRayTuningClicks（2026-09-30）。
        //    ⚠️ 按钮区域让位（2026-10-13）：活按钮默认位在小人框底（同一矩形内），
        //    且本 HEAD 拦截先于控件分发 —— 不避让则门禁命中（空手 + 主手记忆活工具）
        //    时按钮永远点不到。按钮优先：微调只损失框底一条，「躯干底部」锚点在
        //    按钮上方照常可点。
        if ((button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            && !living_item$overLivingButton(mouseX, mouseY)
            && ToolRayTuningClicks.handle(mouseX, mouseY, button,
                this.leftPos + LivingButtonLayout.INVENTORY_PREVIEW_X1,
                this.topPos + LivingButtonLayout.INVENTORY_PREVIEW_Y1,
                this.leftPos + LivingButtonLayout.INVENTORY_PREVIEW_X2,
                this.topPos + LivingButtonLayout.INVENTORY_PREVIEW_Y2,
                this.menu.getCarried().isEmpty())) {
            ((AbstractContainerScreenAccessor) this).living_item$setSkipNextRelease(true);
            cir.setReturnValue(true);
            return;
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

    /** 光标是否悬在活按钮上 —— 射线微调的按钮避让（按钮优先，见 mouseClicked 注释）。 */
    @Unique
    private boolean living_item$overLivingButton(double mouseX, double mouseY) {
        for (GuiEventListener child : this.children()) {
            if (child instanceof LivingButton button && button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * init 尾部重摆按钮 —— ⚠️ {@code InventoryScreen.init} 在 {@code super.init()}
     * （initButtons 的挂载点，{@code AbstractContainerScreen.init} TAIL）**之后**才应用
     * 配方书的 leftPos 偏移（{@code updateScreenPosition}）⇒ 书展开状态下打开背包，
     * initButtons 拿到的是偏移前的 leftPos，按钮先错位 ≈77px、等下个 containerTick 才自愈
     * （闪一帧）。这里在 leftPos 定格后立即按同一套 resolve 偏移重摆（窗口 resize 走
     * 同一个 init，同款时序一并覆盖）。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void living_item$repositionLivingButton(CallbackInfo ci) {
        int[] def = LivingButtonLayout.defaultOffset(true, this.imageWidth, this.imageHeight);
        int[] pos = LivingButtonLayout.resolve(this, this.imageWidth, this.imageHeight, def[0], def[1]);
        for (GuiEventListener child : this.children()) {
            if (child instanceof LivingButton button) {
                button.setPosition(this.leftPos + pos[0], this.topPos + pos[1]);
            }
        }
    }
}