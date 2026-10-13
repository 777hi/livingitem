package com.qiqi.li.client.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 活按钮位置布局的纯逻辑守卫（2026-10-12）—— 覆盖「应用位置时的屏幕夹取」与「默认位分流」。
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

    @Test
    @DisplayName("默认位分流：背包 = 小人框底居中 (42,70)；其余容器 = 底部中央（chest 176×166 → 80,72 不变）")
    void defaultOffset_dispatch() {
        assertArrayEquals(new int[]{42, 70},
            LivingButtonLayout.defaultOffset(true, 176, 166),
            "生存背包：小人框 (26,8)-(75,78) 底居中、底对齐 —— x=26+(49-16)/2=42, y=78-8=70");

        assertArrayEquals(new int[]{80, 72},
            LivingButtonLayout.defaultOffset(false, 176, 166),
            "其余容器：底部中央（chest 原默认位必须不变）");

        assertArrayEquals(new int[]{89, 42},
            LivingButtonLayout.defaultOffset(false, 195, 136),
            "其余容器按自身 GUI 尺寸计算（195×136 → (195-16)/2=89, 136-94=42）");
    }

    @Test
    @DisplayName("小人框矩形常量 = 原版 InventoryScreen.renderBg 写死实参 (26,8)-(75,78)（改动须对照原版源码）")
    void inventoryPreviewRect_matchesVanilla() {
        // 原版依据：InventoryScreen.renderBg → renderEntityInInventoryFollowsMouse(i+26, j+8, i+75, j+78, …)
        // 改这四个常量 = 改按钮默认位与射线微调命中区，必须先对照原版源码
        assertEquals(26, LivingButtonLayout.INVENTORY_PREVIEW_X1);
        assertEquals(8, LivingButtonLayout.INVENTORY_PREVIEW_Y1);
        assertEquals(75, LivingButtonLayout.INVENTORY_PREVIEW_X2);
        assertEquals(78, LivingButtonLayout.INVENTORY_PREVIEW_Y2);
    }
}
