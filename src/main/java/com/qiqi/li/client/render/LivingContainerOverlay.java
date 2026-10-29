package com.qiqi.li.client.render;

import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.client.input.FarmlandPlantHint;
import com.qiqi.li.client.input.LivingItemInputHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * 容器界面里的活物品<b>自绘叠加层</b>：展开地图 / 方向配置 HUD / 活耕地种植提示 / 生长槽大图。
 *
 * <h3>挂点：{@code ContainerScreenEvent.Render.Foreground}</h3>
 * <p>这四项原先都注入在 {@code AbstractContainerScreen.render} 的 TAIL。2026-10-29 真机：
 * 精妙背包界面里<b>展开地图完全不渲染</b> —— 精妙核心的 {@code StorageScreenBase.render}
 * 把 {@code super.render} <b>抄了一份</b>（{@code renderSuper}）而从不调用父实现
 * ⇒ 父类里的注入字节码永远不执行。同类问题 2026-10-19 已在活流体上踩过一次
 * （见 {@link ContainerFluidOverlay} 的类注释），当时的结论是「形似的问题不止流体」——
 * 这次把剩下的四项一起收编到同一个事件挂点，不再按模组逐个补 Mixin。</p>
 *
 * <h3>坐标</h3>
 * <p>事件在<span>已按 (leftPos, topPos) 平移</span>的位姿里触发（原版与照抄型界面皆然）
 * ⇒ 这里先把位姿重置为 identity，之后一律用屏幕绝对坐标（{@code leftPos + slot.x}），
 * 与原先 render TAIL 的坐标空间完全一致。不重置会多乘一次 leftPos/topPos。</p>
 */
public final class LivingContainerOverlay {

    private LivingContainerOverlay() {}

    /** 事件入口：给当前容器界面画全部活物品叠加层。 */
    public static void render(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen,
                              int mouseX, int mouseY) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.setIdentity();
        try {
            LivingMapOverlay.render(guiGraphics, screen, mouseX, mouseY);
            renderDirectionHud(guiGraphics, screen, mouseX, mouseY);
            renderPlantHint(guiGraphics, screen, mouseX, mouseY);
            LivingFarmlandOverlay.render(guiGraphics, screen);
        } finally {
            pose.popPose();
        }
    }

    /**
     * 方向配置 HUD（常驻自定义 tooltip，2026-10-10 用户拍板「排队」方案）：
     * 背景/边框/文字整条走批量缓冲队列，按调用顺序排在所有物品图标之后 ⇒ 天然在其上。
     */
    private static void renderDirectionHud(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen,
                                           int mouseX, int mouseY) {
        List<Component> lines = LivingItemInputHandler.getHudLines();
        if (lines == null || lines.isEmpty()) return;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof LivingButton button && button.isMouseOver(mouseX, mouseY)) {
                lines.addAll(button.getContainerInfoLines());
                break;
            }
        }
        guiGraphics.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
    }

    /**
     * 活耕地种植悬停提示（2026-10-12）：光标持活种子 + 悬停活耕地时，在鼠标处
     * 渲染「种植 / 消耗 / 不足」提示。⚠️ 原版对「光标非空」的悬停不渲染 slot tooltip
     * （{@code AbstractContainerScreen#renderTooltip} 判 {@code carried.isEmpty()}）
     * ⇒ 两者永不重叠，无需抑制原版 tooltip。
     */
    private static void renderPlantHint(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen,
                                        int mouseX, int mouseY) {
        List<Component> lines = FarmlandPlantHint.getHintLines(screen.getMenu(), screen.getSlotUnderMouse());
        if (lines == null || lines.isEmpty()) return;
        guiGraphics.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
    }
}
