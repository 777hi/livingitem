package com.qiqi.li.client.render.tlm;

/** 活魂符预览缩放合成：每个倍率只乘一次。 */
final class SoulSlabPreviewScale {
    static final float BASE_SCALE = 8.0F;

    private SoulSlabPreviewScale() {}

    static float compose(float modelScale, float configMultiplier, int usableHeight, float pulseScale) {
        if (!Float.isFinite(modelScale) || modelScale <= 0
                || !Float.isFinite(configMultiplier) || configMultiplier <= 0
                || usableHeight <= 0 || !Float.isFinite(pulseScale) || pulseScale <= 0) {
            throw new IllegalArgumentException("Invalid soul slab preview scale input");
        }
        return BASE_SCALE * modelScale * configMultiplier * usableHeight / 16.0F * pulseScale;
    }
}
