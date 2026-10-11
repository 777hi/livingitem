package com.qiqi.li.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link AbstractContainerScreen#skipNextRelease}.
 * Used by child-class Mixins that cannot @Shadow the private inherited field without a refMap.
 *
 * <p>⚠️ <b>方法名必须带 {@code living_item$} 前缀</b>：Mixin 的 @Accessor 会在目标类上生成
 * 与原版字段同推导的通用方法名（如 {@code getSkipNextRelease}）。该字段是<b>原版私有字段</b>，
 * 任何其它模组只要也读它、起了同名方法就会和本模组撞车（同 crash 的 SlotWrapperAccessor 一类）。
 * 加 {@code living_item$} 前缀保证合成方法名全局唯一，从根上消除这类冲突。</p>
 *
 * <p>🔴 <b>加前缀的代价（2026-10-11 实测踩坑）</b>：Mixin 靠方法名的 {@code get}/{@code is}/{@code set}
 * 前缀<b>推断目标字段名</b>，加了 {@code living_item$} 前缀后推断失效，启动即抛
 * {@code InvalidAccessorException: Failed to inflect target name ... supported prefixes: [get, is]}。
 * 故带前缀的方法<b>必须显式指定字段名</b> {@code @Accessor("skipNextRelease")}，二者缺一不可。</p>
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("skipNextRelease")
    boolean living_item$getSkipNextRelease();

    @Accessor("skipNextRelease")
    void living_item$setSkipNextRelease(boolean skip);
}