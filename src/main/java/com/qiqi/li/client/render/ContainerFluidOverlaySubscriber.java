package com.qiqi.li.client.render;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * 容器级活流体覆盖层的事件挂点（2026-10-19）。
 *
 * <h3>为什么用事件而不是 {@code render} 的 Mixin</h3>
 * <p>原先挂在 {@link AbstractContainerScreen#render} TAIL。对原版没问题，
 * 精妙背包的 {@code StorageScreenBase.render} <b>抄了一份 {@code super.render}
 * 而不调用父实现</b> ⇒ 父类里的注入字节码永远不执行 ⇒ 背包 GUI 死活不渲染活流体。</p>
 *
 * <p>{@code Render.Foreground} 原版会发、精妙照抄的那段也会发 ⇒ 一个挂点通吃，
 * 不必给每个模组补 Mixin。实现见 {@link ContainerFluidOverlay}。</p>
 */
@EventBusSubscriber
public final class ContainerFluidOverlaySubscriber {

    private ContainerFluidOverlaySubscriber() {}

    @SubscribeEvent
    public static void onRenderForeground(ContainerScreenEvent.Render.Foreground event) {
        if (!(event.getContainerScreen() instanceof AbstractContainerScreen<?> screen)) return;
        ContainerFluidOverlay.render(event.getGuiGraphics(), screen);
    }
}
