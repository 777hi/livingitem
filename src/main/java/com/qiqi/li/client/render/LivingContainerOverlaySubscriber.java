package com.qiqi.li.client.render;

import com.qiqi.li.LivingItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * 活物品容器界面叠加层的渲染挂点。
 *
 * <p>原版与「照抄 {@code super.render}」的第三方界面<b>都会发</b>
 * {@code ContainerScreenEvent.Render.Foreground} ⇒ 一个挂点通吃，
 * 不必给每个模组补 Mixin。实现见 {@link LivingContainerOverlay}。</p>
 */
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = Dist.CLIENT)
public final class LivingContainerOverlaySubscriber {

    private LivingContainerOverlaySubscriber() {}

    @SubscribeEvent
    public static void onRenderForeground(ContainerScreenEvent.Render.Foreground event) {
        if (!(event.getContainerScreen() instanceof AbstractContainerScreen<?> screen)) return;
        LivingContainerOverlay.render(event.getGuiGraphics(), screen, event.getMouseX(), event.getMouseY());
    }
}
