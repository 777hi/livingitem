package com.qiqi.li.client.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** HUD快捷栏物品的可选模型替换桥；未安装联动时原样绘制。 */
public final class LivingSoulSlabHotbarPreview {
    public interface Renderer {
        void begin();
        void render(GuiGraphics graphics, LivingEntity entity, ItemStack stack,
                    int x, int y, int seed, Runnable original);
        void end();
    }

    private static final Renderer ORIGINAL = new Renderer() {
        @Override public void begin() {}
        @Override public void render(GuiGraphics graphics, LivingEntity entity, ItemStack stack,
                                     int x, int y, int seed, Runnable original) {
            original.run();
        }
        @Override public void end() {}
    };
    private static Renderer renderer = ORIGINAL;

    private LivingSoulSlabHotbarPreview() {}

    public static void install(Renderer renderer) {
        LivingSoulSlabHotbarPreview.renderer = Objects.requireNonNull(renderer);
    }

    public static void begin() {
        renderer.begin();
    }

    public static void render(GuiGraphics graphics, LivingEntity entity, ItemStack stack,
                              int x, int y, int seed, Runnable original) {
        renderer.render(graphics, entity, stack, x, y, seed, original);
    }

    public static void end() {
        renderer.end();
    }
}
