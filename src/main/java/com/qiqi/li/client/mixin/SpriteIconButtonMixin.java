package com.qiqi.li.client.mixin;

import com.qiqi.li.client.mixinsupport.MutableSpriteSpriteIconButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * SpriteIconButton Mixin —— 使 final 的 sprite 字段可变。
 *
 * 为什么需要这个 Mixin：
 *   {@link com.qiqi.li.client.gui.LivingButton} 需要根据悬停状态动态切换贴图，
 *   但 SpriteIconButton 的 sprite 字段是 final 的，无法在构造后修改。
 *   通过 @Mutable 注解将 sprite 设为可变，配合 {@link MutableSpriteSpriteIconButton} 接口
 *   提供类型安全的 setSprite 方法。
 */
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