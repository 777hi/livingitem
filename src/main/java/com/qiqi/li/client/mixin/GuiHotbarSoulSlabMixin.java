package com.qiqi.li.client.mixin;

import com.qiqi.li.client.render.LivingSoulSlabHotbarPreview;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将原版快捷栏物品模型绘制交给可选的活魂符预览桥。 */
@Mixin(Gui.class)
public abstract class GuiHotbarSoulSlabMixin {
    @Inject(method = "renderItemHotbar", at = @At("HEAD"))
    private void living_item$beginSoulSlabHotbar(GuiGraphics graphics, DeltaTracker deltaTracker,
                                                  CallbackInfo ci) {
        LivingSoulSlabHotbarPreview.begin();
    }

    @Redirect(
            method = "renderSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V"
            )
    )
    private void living_item$renderHotbarSoulSlab(GuiGraphics graphics, LivingEntity entity,
                                                   ItemStack stack, int x, int y, int seed) {
        LivingSoulSlabHotbarPreview.render(graphics, entity, stack, x, y, seed,
                () -> graphics.renderItem(entity, stack, x, y, seed));
    }

    @Inject(method = "renderItemHotbar", at = @At("RETURN"))
    private void living_item$endSoulSlabHotbar(GuiGraphics graphics, DeltaTracker deltaTracker,
                                                CallbackInfo ci) {
        LivingSoulSlabHotbarPreview.end();
    }
}
