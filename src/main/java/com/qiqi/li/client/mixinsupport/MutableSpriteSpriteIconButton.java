package com.qiqi.li.client.mixinsupport;

import net.minecraft.resources.ResourceLocation;

/**
 * 可变贴图精灵按钮接口 —— 提供 setSprite 方法。
 *
 * 为什么用接口而不是直接在 Mixin 中调用：
 *   Java 不允许从外部类直接访问 Mixin 添加的方法。
 *   通过让 Mixin 类实现此接口，外部代码可以通过接口类型转换来调用 setSprite，
 *   实现类型安全的贴图动态切换。
 *
 * 使用示例：
 *   ((MutableSpriteSpriteIconButton) button).setSprite(newSpriteLocation);
 *
 * @see com.qiqi.li.client.mixin.SpriteIconButtonMixin Mixin 实现
 * @see com.qiqi.li.client.gui.LivingButton 使用方
 */
public interface MutableSpriteSpriteIconButton {
    void setSprite(ResourceLocation location);
}