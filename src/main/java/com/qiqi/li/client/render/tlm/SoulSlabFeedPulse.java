package com.qiqi.li.client.render.tlm;

/** 活魂符投喂的短促缩放曲线。 */
final class SoulSlabFeedPulse {
    static final int DURATION_TICKS = 6;

    private SoulSlabFeedPulse() {}

    static float scale(int ticksLeft, float partial) {
        if (ticksLeft <= 0) return 1;
        float progress = Math.clamp((DURATION_TICKS - ticksLeft + partial) / DURATION_TICKS, 0, 1);
        return 1 + 0.12f * (float) Math.sin(Math.PI * progress);
    }
}
