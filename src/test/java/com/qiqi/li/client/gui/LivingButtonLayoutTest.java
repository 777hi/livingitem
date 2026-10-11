package com.qiqi.li.client.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 活按钮位置布局的纯逻辑守卫（2026-10-12）—— 目前只覆盖「应用位置时的屏幕夹取」。
 *
 * <p>⚠️ 背景：拖动时的夹取只管**当时那一次**；玩家之后缩小窗口 / 调大 GUI scale
 * ⇒ 逻辑屏幕变小，而保存的偏移是**相对 GUI** 的 ⇒ 换算出的屏幕坐标可能落到屏外
 * （按钮"消失"）。本夹取就是补这一段。</p>
 */
class LivingButtonLayoutTest {

    @Test
    @DisplayName("屏幕内 ⇒ 原样；越界 ⇒ 夹到边缘；屏幕比按钮还小 ⇒ 夹到 0（不产生负上界）")
    void clampToScreen() {
        assertArrayEquals(new int[]{100, 50},
            LivingButtonLayout.clampToScreen(100, 50, 400, 300, 16, 8),
            "屏幕内不动");

        assertArrayEquals(new int[]{384, 292},
            LivingButtonLayout.clampToScreen(500, 400, 400, 300, 16, 8),
            "右下越界 ⇒ 夹到 (屏宽-按钮宽, 屏高-按钮高)");

        assertArrayEquals(new int[]{0, 0},
            LivingButtonLayout.clampToScreen(-20, -5, 400, 300, 16, 8),
            "左上越界（负数）⇒ 夹到 0");

        assertArrayEquals(new int[]{0, 0},
            LivingButtonLayout.clampToScreen(5, 5, 10, 4, 16, 8),
            "屏幕比按钮还小 ⇒ 上界取 max(0, …) 而非负数");
    }
}
