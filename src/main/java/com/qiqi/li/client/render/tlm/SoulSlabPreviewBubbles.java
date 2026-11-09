package com.qiqi.li.client.render.tlm;

import net.minecraft.util.RandomSource;

/** 预览图片表情的原配置判定；未选中图片时不生成服务端颜文字。 */
final class SoulSlabPreviewBubbles {
    private SoulSlabPreviewBubbles() {}

    static boolean shouldGenerate(boolean visible, boolean enabled, boolean empty, long tick,
                                  long identityOffset, int checkRate, int imageWeight,
                                  int textWeight, RandomSource random) {
        if (!visible || !enabled || !empty || checkRate <= 0 || imageWeight <= 0 || textWeight < 0) return false;
        long phase = Math.floorMod(tick, checkRate) + Math.floorMod(identityOffset, checkRate);
        if (phase % checkRate != 0) return false;
        long total = (long) imageWeight + textWeight;
        if (total <= 0 || total > Integer.MAX_VALUE) return false;
        return random.nextInt((int) total) < imageWeight;
    }
}
