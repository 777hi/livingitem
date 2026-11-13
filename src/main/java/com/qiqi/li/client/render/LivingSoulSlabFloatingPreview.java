package com.qiqi.li.client.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** 容器光标物品的可选模型替换桥；未安装联动时原样绘制。 */
public final class LivingSoulSlabFloatingPreview {
    @FunctionalInterface
    public interface Renderer {
        void render(AbstractContainerScreen<?> screen, GuiGraphics graphics, ItemStack stack,
                    int x, int y, boolean snapback, Runnable original);
    }

    private static final Renderer ORIGINAL = (screen, graphics, stack, x, y, snapback, original) -> original.run();
    private static Renderer renderer = ORIGINAL;

    private LivingSoulSlabFloatingPreview() {}

    public static void install(Renderer renderer) {
        LivingSoulSlabFloatingPreview.renderer = Objects.requireNonNull(renderer);
    }

    public static void render(AbstractContainerScreen<?> screen, GuiGraphics graphics, ItemStack stack,
                              int x, int y, boolean snapback, Runnable original) {
        renderer.render(screen, graphics, stack, x, y, snapback, original);
    }
}
