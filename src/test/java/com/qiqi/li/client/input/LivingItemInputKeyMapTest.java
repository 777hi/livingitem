package com.qiqi.li.client.input;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * 方向键码表守卫（2026-10-29）—— 方向输入主通道的「键码 → 方向字符」映射与字符归一化。
 *
 * <p>守两条契约：
 *   1. 九宫格 8 键（W/A/S/D + Q/E/Z/C）与 {@code keyToDirection(char)} 的字符逐一对应，
 *      其余键码一律 0（= 不消费）；
 *   2. 全角字母（输入法提交态）归一化为半角 ASCII，字符兜底通道仍能识别方向。
 */
class LivingItemInputKeyMapTest {

    @Test
    @DisplayName("键码 → 方向字符：九宫格 8 键齐全，非方向键为 0")
    void directionCharForKeyCode() {
        assertAll(
            () -> assertEquals('W', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_W)),
            () -> assertEquals('S', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_S)),
            () -> assertEquals('A', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_A)),
            () -> assertEquals('D', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_D)),
            () -> assertEquals('Q', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_Q)),
            () -> assertEquals('E', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_E)),
            () -> assertEquals('Z', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_Z)),
            () -> assertEquals('C', LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_C)),
            () -> assertEquals(0, LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_SPACE)),
            () -> assertEquals(0, LivingItemInputHandler.directionCharForKeyCode(GLFW.GLFW_KEY_ESCAPE))
        );
    }

    @Test
    @DisplayName("全角字母归一化：全角大小写 → 半角，其余字符原样")
    void normalizeChar() {
        assertAll(
            () -> assertEquals('w', LivingItemInputHandler.normalizeChar('\uFF57')),
            () -> assertEquals('W', LivingItemInputHandler.normalizeChar('\uFF37')),
            // 字符通道实际链路：归一化 → 转大写 → 查方向表
            () -> assertEquals('W',
                Character.toUpperCase(LivingItemInputHandler.normalizeChar('\uFF57'))),
            () -> assertEquals('A', LivingItemInputHandler.normalizeChar('A')),
            () -> assertEquals('\u4E2D', LivingItemInputHandler.normalizeChar('\u4E2D'))
        );
    }
}
