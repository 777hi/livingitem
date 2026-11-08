package com.qiqi.li.client.mixin;

import com.qiqi.li.client.render.tlm.LivingSoulSlabSlotPreview;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/** 只替换真实槽的底图；carried、假槽与拖放预测保持原调用。 */
@Mixin(AbstractContainerScreen.class)
public abstract class LivingSoulSlabScreenMixin {
    @Shadow protected boolean isQuickCrafting;
    @Shadow protected Set<Slot> quickCraftSlots;

    @Redirect(method = "renderSlotContents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/item/ItemStack;III)V"))
    private void living_item$renderSoulSlab(GuiGraphics graphics, ItemStack stack, int x, int y, int seed,
                                          GuiGraphics originalGraphics, ItemStack originalStack, Slot slot,
                                          String countString) {
        var screen = (AbstractContainerScreen<?>) (Object) this;
        if (slot.isFake() || (isQuickCrafting && quickCraftSlots.contains(slot))
                || !ItemStack.matches(stack, slot.getItem())
                || !LivingSoulSlabSlotPreview.prepare(screen, graphics, stack, slot, x, y, seed, countString)) {
            graphics.renderItem(stack, x, y, seed);
        }
    }

    @Redirect(method = "renderSlotContents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;renderFakeItem(Lnet/minecraft/world/item/ItemStack;III)V"))
    private void living_item$keepFakeSoulSlab(GuiGraphics graphics, ItemStack stack, int x, int y, int seed) {
        graphics.renderFakeItem(stack, x, y, seed);
    }
}
