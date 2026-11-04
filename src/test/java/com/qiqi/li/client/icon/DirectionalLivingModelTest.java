package com.qiqi.li.client.icon;

import static org.junit.jupiter.api.Assertions.*;

import com.qiqi.li.living.model.Pos2D;
import org.junit.jupiter.api.Test;

class DirectionalLivingModelTest {

    @Test
    void rotationTableIsPinnedForInGameTuning() {
        // 家族 GUI 视觉角：基准 = 图标内容朝上，GUI 屏面 y 向下 ⇒ 正角视觉顺时针。
        assertEquals(0, DirectionalLivingModel.rotationOf(Pos2D.UP));
        assertEquals(90, DirectionalLivingModel.rotationOf(Pos2D.RIGHT));
        assertEquals(180, DirectionalLivingModel.rotationOf(Pos2D.DOWN));
        assertEquals(-90, DirectionalLivingModel.rotationOf(Pos2D.LEFT));
    }

    @Test
    void nonCardinalDirectionFallsBackToUpInsteadOfCrashing() {
        assertEquals(0, DirectionalLivingModel.rotationOf(Pos2D.UP_RIGHT),
            "对角方向落兜底（组件层已拦，防御性兜底只防渲染崩溃）");
    }

    @Test
    void modelPipelineNegationMatchesLegacyFamilyTable() {
        // 物品渲染管线在 applyTransform 之后含 y 翻转 ⇒ TorchRenderState 施加相反数；
        // 相反数表必须与既有火把家族实机验证值逐项一致（UP=0 / RIGHT=-90 / DOWN=180 / LEFT=90）。
        assertEquals(0, -DirectionalLivingModel.rotationOf(Pos2D.UP));
        assertEquals(-90, -DirectionalLivingModel.rotationOf(Pos2D.RIGHT));
        assertEquals(180, Math.floorMod(-DirectionalLivingModel.rotationOf(Pos2D.DOWN), 360));
        assertEquals(90, -DirectionalLivingModel.rotationOf(Pos2D.LEFT));
    }
}
