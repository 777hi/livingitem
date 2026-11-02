package com.qiqi.li.client.icon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import com.qiqi.li.living.model.Pos2D;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PistonRenderStateTest {

    private static final String KEY = "C5";

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
    void headShortFollowsHalfwayRule() {
        assertTrue(PistonRenderState.headShort(0f));
        assertTrue(PistonRenderState.headShort(0.499f));
        assertFalse(PistonRenderState.headShort(0.5f));
        assertFalse(PistonRenderState.headShort(1f));
    }

    @Test
    void headOffsetIsLinearOverFullSlotTravel() {
        assertEquals(0f, PistonRenderState.headOffsetUnits(0f), 1e-6);
        assertEquals(0.25f, PistonRenderState.headOffsetUnits(0.25f), 1e-6);
        assertEquals(1f, PistonRenderState.headOffsetUnits(1f), 1e-6);
    }

    @Test
    void rotationTableIsPinnedForInGameTuning() {
        assertEquals(-90f, PistonRenderState.rotationDegrees(Pos2D.UP), 1e-6);
        assertEquals(90f, PistonRenderState.rotationDegrees(Pos2D.DOWN), 1e-6);
        assertEquals(180f, PistonRenderState.rotationDegrees(Pos2D.LEFT), 1e-6);
        assertEquals(0f, PistonRenderState.rotationDegrees(Pos2D.RIGHT), 1e-6);
    }

    @Test
    void nonCardinalDirectionFallsBackToRightInsteadOfCrashing() {
        assertEquals(0f, PistonRenderState.rotationDegrees(Pos2D.UP_RIGHT), 1e-6,
            "对角方向落兜底（组件层已拦，防御性兜底只防渲染崩溃）");
        assertEquals(0f, PistonRenderState.advance(KEY, true, Pos2D.UP_RIGHT, 1_000), 1e-6);
    }
}
