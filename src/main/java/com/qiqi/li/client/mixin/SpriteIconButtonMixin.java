package com.qiqi.li.client.mixin;

import com.qiqi.li.client.mixinsupport.MutableSpriteSpriteIconButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SpriteIconButton.class)
public class SpriteIconButtonMixin implements MutableSpriteSpriteIconButton {
    @Mutable
    @Shadow
    @Final
    protected ResourceLocation sprite;

    @Override
    public void setSprite(ResourceLocation location) {
        this.sprite = location;
    }
}
