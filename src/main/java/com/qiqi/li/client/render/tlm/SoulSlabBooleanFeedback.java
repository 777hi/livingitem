package com.qiqi.li.client.render.tlm;

/** 同步布尔状态的边沿观察器；首次观察只建立基线。 */
final class SoulSlabBooleanFeedback {
    private boolean initialized;
    private boolean observed;

    boolean observe(boolean current) {
        if (!initialized) {
            initialized = true;
            observed = current;
            return false;
        }
        boolean changed = observed != current;
        observed = current;
        return changed;
    }
}
