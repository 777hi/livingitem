package com.qiqi.li.client.render.tlm;

/** 预览所在的绘制表面及跨位置动画决策。 */
final class SoulSlabPreviewSurface {
    enum Kind { HUD_SLOT, GUI_SLOT, CURSOR }
    enum Motion { NONE, MOVE, SETTLE }

    private SoulSlabPreviewSurface() {}

    static Motion transition(Kind source, Kind target, boolean samePosition) {
        if (source != target) return Motion.SETTLE;
        if (samePosition) return Motion.NONE;
        return source == Kind.GUI_SLOT ? Motion.MOVE : Motion.SETTLE;
    }

    static boolean hudOwnsFrame(boolean containerScreenOpen) {
        return !containerScreenOpen;
    }
}
