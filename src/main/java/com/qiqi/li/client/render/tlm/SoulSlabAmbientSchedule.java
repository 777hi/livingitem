package com.qiqi.li.client.render.tlm;

/** 复现原版Mob环境音的后增计数节奏。 */
final class SoulSlabAmbientSchedule {
    private static final int INTERVAL = 120;
    private int time;

    boolean tick(int roll) {
        boolean due = roll < time++;
        if (due) time = -INTERVAL;
        return due;
    }

    void reset() {
        time = 0;
    }

    int time() {
        return time;
    }
}
