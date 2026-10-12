package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.client.render.ProgressBarDecorator.Bar;
import com.qiqi.li.client.render.ProgressBarDecorator.Orientation;

/**
 * 通用进度条的纯判据单测。
 *
 * <p>渲染本体需要 {@code GuiGraphics}（客户端），单测只钉两件可脱离 Minecraft 的事：
 * <b>守卫</b>（{@link ProgressBarDecorator#shouldDraw}）与<b>几何</b>
 * （{@link ProgressBarDecorator#trackRect} / {@link ProgressBarDecorator#fillRect}）。
 * 几何数值直接对齐原版耐久条（{@code GuiGraphics.renderItemDecorations} 源码：
 * 黑底 13×2、彩条 13×1、取整用 {@code Math.round}）。</p>
 */
class ProgressBarDecoratorTest {

    // ============================================================
    // 守卫
    // ============================================================

    @Test
    @DisplayName("守卫：无规格不画、比例 ≤0 不画、>0 才画")
    void shouldDraw_guard() {
        assertFalse(ProgressBarDecorator.shouldDraw(null), "域没给规格 ⇒ 不画");

        assertFalse(ProgressBarDecorator.shouldDraw(Bar.horizontal(0.0F, 0xFFFFFFFF)),
            "比例 0（资源耗尽）⇒ 不画 —— 「有货才画」口径，与耐久条「有损才画」互补");
        assertFalse(ProgressBarDecorator.shouldDraw(Bar.horizontal(-0.5F, 0xFFFFFFFF)),
            "负比例（理论上不该出现）⇒ 不画");

        assertTrue(ProgressBarDecorator.shouldDraw(Bar.horizontal(0.01F, 0xFFFFFFFF)),
            "哪怕只剩一点也画");
        assertTrue(ProgressBarDecorator.shouldDraw(Bar.horizontal(1.0F, 0xFFFFFFFF)));
    }

    // ============================================================
    // 几何：黑底
    // ============================================================

    @Test
    @DisplayName("黑底几何：横 = 原版耐久条位置（左下 13×2）；竖 = 左缘 2×13")
    void trackRect_geometry() {
        assertArrayEquals(new int[] {2, 13, 15, 15}, ProgressBarDecorator.trackRect(Orientation.HORIZONTAL),
            "横条照抄原版：x+2..x+15 / y+13..y+15");
        assertArrayEquals(new int[] {1, 2, 3, 15}, ProgressBarDecorator.trackRect(Orientation.VERTICAL),
            "竖条在左缘：2px 宽、13px 高（与横条共用 y 底边）");
    }

    // ============================================================
    // 几何：彩条
    // ============================================================

    @Test
    @DisplayName("横条填充：从左往右长，满格 13px、半格 7px（原版 Math.round）、0 为零宽")
    void fillRect_horizontal() {
        assertArrayEquals(new int[] {2, 13, 15, 14}, ProgressBarDecorator.fillRect(Orientation.HORIZONTAL, 1.0F),
            "满格 = 13px 整条");
        assertArrayEquals(new int[] {2, 13, 9, 14}, ProgressBarDecorator.fillRect(Orientation.HORIZONTAL, 0.5F),
            "半格 = round(6.5) = 7px");
        assertArrayEquals(new int[] {2, 13, 2, 14}, ProgressBarDecorator.fillRect(Orientation.HORIZONTAL, 0.0F),
            "零宽（守卫本就不会画，纯函数仍自洽）");
    }

    @Test
    @DisplayName("竖条填充：从底往上长，满格顶到 y+2、半格到 y+8")
    void fillRect_vertical() {
        assertArrayEquals(new int[] {1, 2, 3, 15}, ProgressBarDecorator.fillRect(Orientation.VERTICAL, 1.0F),
            "满格 = 从 y+15 长到 y+2");
        assertArrayEquals(new int[] {1, 8, 3, 15}, ProgressBarDecorator.fillRect(Orientation.VERTICAL, 0.5F),
            "半格 = 高 7px，顶部到 y+8");
        assertArrayEquals(new int[] {1, 15, 3, 15}, ProgressBarDecorator.fillRect(Orientation.VERTICAL, 0.0F),
            "零高");
    }

    // ============================================================
    // 与域口径对齐：燃料「一格一次」
    // ============================================================

    @Test
    @DisplayName("燃料刻度：1/20 次 = 1px（看得见一格）、20/20 = 整条")
    void fuelScale_oneUseIsVisible() {
        float oneUse = 1.0F / 20.0F;   // BrewingBatchLogic.FUEL_USES
        int[] one = ProgressBarDecorator.fillRect(Orientation.HORIZONTAL, oneUse);
        assertArrayEquals(new int[] {2, 13, 3, 14}, one,
            "剩 1 次烈焰粉 = 1px —— 一次之差肉眼可辨（13px / 20 次 = 0.65px 向上取整）");

        int[] full = ProgressBarDecorator.fillRect(Orientation.HORIZONTAL, 20 * oneUse);
        assertArrayEquals(new int[] {2, 13, 15, 14}, full, "20 次 = 满格");
    }
}
