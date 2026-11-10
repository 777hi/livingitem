package com.qiqi.li.client.render.tlm;

/** 活魂符预览的可见帧判据。 */
final class SoulSlabPreviewVisibility {
    private SoulSlabPreviewVisibility() {}

    static boolean recentlyDrawn(int tick, int seenTick) {
        long age = (long) tick - seenTick;
        return age >= 0 && age <= 2;
    }
}
