package com.qiqi.li.client.mixin;

import com.qiqi.li.client.util.LivingChestTabState;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookPage.class)
public class RecipeBookPageMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRenderHead(CallbackInfo ci) {
        if (LivingChestTabState.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void onRenderTooltipHead(CallbackInfo ci) {
        if (LivingChestTabState.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClickedHead(CallbackInfoReturnable<Boolean> cir) {
        if (LivingChestTabState.isActive()) {
            cir.setReturnValue(false);
        }
    }
}