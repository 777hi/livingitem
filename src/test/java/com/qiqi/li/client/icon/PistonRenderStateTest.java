package com.qiqi.li.client.icon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import com.qiqi.li.living.model.Pos2D;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PistonRenderStateTest {

    private static final String KEY = "C5";
    private static final double SCALE_X = 18.0 / (Math.sqrt(10.0) / 4.0 * (1.0 + 3.0 / 8.0));

    @AfterEach
    void clean() {
        PistonRenderState.clearForTest();
    }

    @Test
    void extendsOverDurationAndClampsAtFull() {
        long t0 = 1_000;
        assertEquals(0f, PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0), 1e-6);
        assertEquals(0.5f, PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0 + 50), 1e-6);
        assertEquals(1f, PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0 + 100), 1e-6);
        assertEquals(1f, PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0 + 999), 1e-6,
            "到顶保持，不越界");
    }

    @Test
    void retractsFromCurrentProgressAndClampsAtZero() {
        long t0 = 1_000;
        PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0);
        PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0 + 100);   // 到 1
        assertEquals(1f, PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 100), 1e-6);
        assertEquals(0.75f, PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 125), 1e-6);
        assertEquals(0f, PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 200), 1e-6);
        assertEquals(0f, PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 999), 1e-6);
    }

    @Test
    void flipMidAnimationContinuesFromCurrentProgress() {
        long t0 = 1_000;
        PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0);
        float half = PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0 + 50);
        assertEquals(0.5f, half, 1e-6);
        assertEquals(0.5f, PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 50), 1e-6,
            "同刻翻转 ⇒ 从当前进度 0.5 起收，不跳变");
        assertEquals(0.25f, PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 75), 1e-6);
    }

    @Test
    void directionChangeSnapsInsteadOfSliding() {
        long t0 = 1_000;
        PistonRenderState.advance(KEY, true, Pos2D.UP, t0);
        PistonRenderState.advance(KEY, true, Pos2D.UP, t0 + 50);
        assertEquals(1f, PistonRenderState.advance(KEY, true, Pos2D.RIGHT, t0 + 60), 1e-6,
            "伸出中改方向 ⇒ 吸附到全伸，不沿旧方向滑动");
        PistonRenderState.advance(KEY, false, Pos2D.RIGHT, t0 + 60);
        assertEquals(0f, PistonRenderState.advance(KEY, false, Pos2D.DOWN, t0 + 61), 1e-6,
            "收回中改方向 ⇒ 吸附到全收");
    }

    @Test
    void keysAreIndependent() {
        long t0 = 1_000;
        PistonRenderState.advance("C1", true, Pos2D.RIGHT, t0);
        PistonRenderState.advance("C2", true, Pos2D.RIGHT, t0);
        assertEquals(0.5f, PistonRenderState.advance("C1", true, Pos2D.RIGHT, t0 + 50), 1e-6);
        assertEquals(0.1f, PistonRenderState.advance("C2", true, Pos2D.RIGHT, t0 + 10), 1e-6,
            "C2 只走了 10ms ⇒ 0.1，与 C1 互不影响");
    }

    @Test
    void pruneKeepsOnlyActiveKeys() {
        long t0 = 1_000;
        PistonRenderState.advance("C1", true, Pos2D.RIGHT, t0);
        PistonRenderState.advance("C2", true, Pos2D.RIGHT, t0);
        PistonRenderState.prune(Set.of("C2"));
        assertEquals(0f, PistonRenderState.advance("C1", true, Pos2D.RIGHT, t0 + 999), 1e-6,
            "被剪枝的键重新出现 ⇒ 从 0 重来");
        assertEquals(1f, PistonRenderState.advance("C2", true, Pos2D.RIGHT, t0 + 100), 1e-6,
            "活跃键状态保留");
    }

    @Test
    void headOffsetIsLinearOverFullSlotTravel() {
        assertEquals(0f, PistonRenderState.headOffsetUnits(0f), 1e-6);
        assertEquals(0.25f, PistonRenderState.headOffsetUnits(0.25f), 1e-6);
        assertEquals(1f, PistonRenderState.headOffsetUnits(1f), 1e-6);
    }

    @Test
    void rotationTableIsPinnedForInGameTuning() {
        assertEquals(63.43495f, PistonRenderState.rotationDegrees(Pos2D.UP), 1e-5);
        assertEquals(243.43495f, PistonRenderState.rotationDegrees(Pos2D.DOWN), 1e-5);
        assertEquals(333.43495f, PistonRenderState.rotationDegrees(Pos2D.LEFT), 1e-5);
        assertEquals(153.43495f, PistonRenderState.rotationDegrees(Pos2D.RIGHT), 1e-5);
    }

    @Test
    void fullExtensionHeadPlateCenterReachesCanonicalSlotCenters() {
        assertProjectedCenter(Pos2D.RIGHT, 1f, 18.0, 0.0);
        assertProjectedCenter(Pos2D.DOWN, 1f, 0.0, 18.0);
        assertProjectedCenter(Pos2D.LEFT, 1f, -18.0, 0.0);
        assertProjectedCenter(Pos2D.UP, 1f, 0.0, -18.0);
    }

    @Test
    void animatedOffsetPortionRemainsLinear() {
        double[] start = projectHeadCenter(Pos2D.RIGHT, 0f);
        double[] half = projectHeadCenter(Pos2D.RIGHT, 0.5f);
        double[] full = projectHeadCenter(Pos2D.RIGHT, 1f);

        assertEquals((start[0] + full[0]) / 2.0, half[0], 1e-5);
        assertEquals((start[1] + full[1]) / 2.0, half[1], 1e-5);
        assertNotEquals(full[0] / 2.0, half[0], 1e-5,
            "头板自身 +3/8 偏移固定；线性的是动画位移部分，不是相对基座的中心坐标");
    }

    @Test
    void nonCardinalDirectionFallsBackToRightInsteadOfCrashing() {
        assertEquals(153.43495f, PistonRenderState.rotationDegrees(Pos2D.UP_RIGHT), 1e-5,
            "对角方向落兜底（组件层已拦，防御性兜底只防渲染崩溃）");
        assertEquals(0f, PistonRenderState.advance(KEY, true, Pos2D.UP_RIGHT, 1_000), 1e-6);
    }

    private static void assertProjectedCenter(Pos2D direction, float progress,
                                              double expectedX, double expectedY) {
        double[] projected = projectHeadCenter(direction, progress);
        assertEquals(expectedX, projected[0], 1e-5);
        assertEquals(expectedY, projected[1], 1e-5);
    }

    /** 独立复刻 PoseStack 的 T·Rz·Rx·Ry·S（JOML 右手系），不手抄投影角符号。 */
    private static double[] projectHeadCenter(Pos2D direction, float progress) {
        double distance = PistonRenderState.headOffsetUnits(progress) + 3.0 / 8.0;
        Matrix4f matrix = new Matrix4f()
            .rotateZ((float) Math.toRadians(PistonRenderState.rotationDegrees(direction)))
            .rotateX((float) Math.toRadians(30.0))
            .rotateY((float) Math.toRadians(225.0))
            .scale((float) SCALE_X, 11.25f, 11.25f);
        Vector4f projected = matrix.transform(new Vector4f((float) distance, 0f, 0f, 0f));
        return new double[] { projected.x, projected.y };
    }
}
