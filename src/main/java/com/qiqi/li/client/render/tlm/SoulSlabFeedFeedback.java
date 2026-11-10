package com.qiqi.li.client.render.tlm;

/** 活魂符投喂同步的单调观察器；首次只建基线。 */
final class SoulSlabFeedFeedback {
    private boolean initialized;
    private int observed;

    boolean observe(int pendingFavor) {
        int current = Math.max(0, pendingFavor);
        if (!initialized) {
            initialized = true;
            observed = current;
            return false;
        }
        boolean increased = current > observed;
        observed = current;
        return increased;
    }
}
