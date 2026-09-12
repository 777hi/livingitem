package com.qiqi.li.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link AbstractContainerScreen#skipNextRelease}.
 * Used by child-class Mixins that cannot @Shadow the private inherited field without a refMap.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor
    boolean getSkipNextRelease();

    @Accessor
    void setSkipNextRelease(boolean skip);
}