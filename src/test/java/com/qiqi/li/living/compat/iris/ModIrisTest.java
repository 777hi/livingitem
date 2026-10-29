package com.qiqi.li.living.compat.iris;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Iris 光影兼容门面守卫（2026-10-30）—— 无 Iris 环境的降级路径。
 *
 * <p>守两条契约：
 *   1. Iris 不在装（单测环境恒如此）⇒ {@code tagBeamSource} 返回<b>同一个</b>源实例，
 *      任何几何都绝不经过包装（打标签路径零参与）；
 *   2. 探测链路在有无 FML 的环境下都不抛异常 —— 渲染主循环不容忍 {@code LinkageError}。</p>
 */
class ModIrisTest {

    /** 一旦被调用即失败的源 —— 用它证明「无 Iris 时几何不经包装」。 */
    private static MultiBufferSource explodingSource() {
        return type -> {
            throw new AssertionError("无 Iris 时不应有任何几何经包装源");
        };
    }

    @Test
    @DisplayName("无 Iris：tagBeamSource 原样返回同一实例，几何不经包装")
    void passthroughWithoutIris() {
        MultiBufferSource source = explodingSource();
        assertSame(source, ModIris.tagBeamSource(source));
    }

    @Test
    @DisplayName("探测不抛异常：isLoaded / tagBeamSource 在有无 FML 的环境下都安全")
    void probeDoesNotThrow() {
        assertDoesNotThrow(ModIris::isLoaded);
        assertDoesNotThrow(() -> ModIris.tagBeamSource(explodingSource()));
    }
}
