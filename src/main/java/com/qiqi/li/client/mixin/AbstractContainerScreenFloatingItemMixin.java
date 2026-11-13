package com.qiqi.li.client.mixin;

import com.qiqi.li.client.render.LivingSoulSlabFloatingPreview;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将原版光标物品模型绘制交给可选的活魂符预览桥。 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenFloatingItemMixin {
    @Unique
    private boolean living_item$renderingSnapback;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderFloatingItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
                    ordinal = 0
            )
    )
    private void living_item$markCarriedDraw(GuiGraphics graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        this.living_item$renderingSnapback = false;
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderFloatingItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
                    ordinal = 1
            ),
            require = 0
    )
    private void living_item$markSnapbackDraw(GuiGraphics graphics, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        this.living_item$renderingSnapback = true;
    }

    @Redirect(
            method = "renderFloatingItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/item/ItemStack;II)V"
            )
    )
    private void living_item$renderFloatingSoulSlab(GuiGraphics graphics, ItemStack stack, int x, int y) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        boolean snapback = this.living_item$renderingSnapback;
        this.living_item$renderingSnapback = false;
        LivingSoulSlabFloatingPreview.render(screen, graphics, stack, x, y, snapback,
                () -> graphics.renderItem(stack, x, y));
    }
}
