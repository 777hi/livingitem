package com.qiqi.li.living.compat.create;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 活水车应力输出状态机；Create 操作经 {@link KineticOps} 端口执行。
 *
 * <pre>
 *   INACTIVE ──apply(rpm≠0)──→ ACTIVE
 *   ACTIVE   ──apply(rpm=0)──→ INACTIVE
 *   ACTIVE   ──expired───────→ INACTIVE
 *   ACTIVE   ──chunkUnload───→ INACTIVE
 * </pre>
 */
public class StressStateMachine {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/StressState");

    private float rpm;
    private float capacity;
    private boolean refreshedThisTick;

    public float getRPM() {
        return rpm;
    }

    public float getCapacity() {
        return capacity;
    }

    public boolean isActive() {
        return rpm != 0;
    }

    /** 静默恢复落盘状态，并给容器重新注入留一拍宽限。 */
    public void restoreFromDisk(float rpm, float capacity) {
        this.rpm = rpm;
        this.capacity = capacity;
        this.refreshedThisTick = true;
    }

    public void tick(KineticOps ops) {
        if (!ops.livingItem$isServerSide()) return;

        if (rpm == 0) {
            refreshedThisTick = false;
            return;
        }

        if (!refreshedThisTick) {
            try {
                if (!ops.livingItem$tryUpdateGeneratedRotation()) {
                    refreshedThisTick = true;
                    if (ops.livingItem$hasNetwork()) {
                        try {
                            ops.livingItem$removeFromNetwork();
                        } catch (Exception e2) {
                            LOGGER.debug("[StressState] Error removing from network on expiry at {}: {}",
                                ops.livingItem$getDebugPosition(), e2.getMessage());
                        }
                    }
                    ops.livingItem$detachKinetics();
                    ops.livingItem$setSpeed(0);
                    ops.livingItem$clearNetwork();
                    ops.livingItem$setChanged();
                    ops.livingItem$sendData();
                }
            } catch (NullPointerException e) {
                LOGGER.debug("[StressState] NPE during expiry cleanup on {} at {}: {}",
                    ops.livingItem$getDebugType(), ops.livingItem$getDebugPosition(), e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("[StressState] Error during expiry cleanup on {} at {}",
                    ops.livingItem$getDebugType(), ops.livingItem$getDebugPosition(), e);
            }
            rpm = 0;
            capacity = 0;
        }

        refreshedThisTick = false;
    }

    public void onChunkUnloaded(KineticOps ops) {
        if (!ops.livingItem$isServerSide()) return;
        if (rpm == 0) return;

        refreshedThisTick = true;

        if (ops.livingItem$hasNetwork()) {
            try {
                ops.livingItem$removeFromNetwork();
            } catch (NullPointerException e) {
                LOGGER.debug("[StressState] NPE removing from network on chunk unload at {}: {}",
                    ops.livingItem$getDebugPosition(), e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("[StressState] Error removing from network on chunk unload at {}",
                    ops.livingItem$getDebugPosition(), e);
            }
        }

        try {
            ops.livingItem$detachKinetics();
        } catch (NullPointerException e) {
            LOGGER.debug("[StressState] NPE detaching kinetics on chunk unload at {}: {}",
                ops.livingItem$getDebugPosition(), e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[StressState] Error detaching kinetics on chunk unload at {}",
                ops.livingItem$getDebugPosition(), e);
        }

        rpm = 0;
        capacity = 0;
        refreshedThisTick = false;
    }

    public void applyStress(KineticOps ops, float newRpm, float newCap) {
        if (!ops.livingItem$isSafeForStressInjection()) {
            if (rpm != 0) {
                rpm = 0;
                capacity = 0;
            }
            return;
        }

        boolean serverSide = ops.livingItem$isServerSide();
        if (serverSide) {
            refreshedThisTick = true;
        }

        float prev = rpm;

        if (!serverSide) {
            rpm = newRpm;
            capacity = newCap;
            return;
        }
        if (Math.abs(prev - newRpm) < 0.01f) {
            rpm = newRpm;
            if (newRpm != 0) {
                updateNetwork(ops, newCap);
            }
            capacity = newCap;
            return;
        }

        try {
            if (prev != 0 && newRpm == 0) {
                if (ops.livingItem$hasNetwork()) {
                    try {
                        ops.livingItem$removeFromNetwork();
                    } catch (Exception e) {
                        LOGGER.debug("[StressState] Error removing from network on stress clear at {}: {}",
                            ops.livingItem$getDebugPosition(), e.getMessage());
                    }
                }
                ops.livingItem$detachKinetics();
                ops.livingItem$setSpeed(0);
                ops.livingItem$clearNetwork();
                rpm = 0;
                capacity = 0;
            } else {
                rpm = newRpm;
                capacity = newCap;
                if (prev == 0 && newRpm != 0) {
                    // ⚠️ 激活必须先设置速度再摘网；顺序反转会让 Create 留下旧网络并销毁方块。
                    ops.livingItem$setSpeed(newRpm);
                    ops.livingItem$detachKinetics();
                    ops.livingItem$setNetworkToSelf();
                    ops.livingItem$attachKinetics();
                    updateNetwork(ops, newCap);
                } else {
                    ops.livingItem$detachKinetics();
                    ops.livingItem$setSpeed(newRpm);
                    ops.livingItem$attachKinetics();
                    updateNetwork(ops, newCap);
                }
            }
            ops.livingItem$setChanged();
            ops.livingItem$sendData();
        } catch (NullPointerException e) {
            LOGGER.debug("[StressState] NPE applying stress on {} at {}: {}",
                ops.livingItem$getDebugType(), ops.livingItem$getDebugPosition(), e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[StressState] Error applying stress on {} at {}",
                ops.livingItem$getDebugType(), ops.livingItem$getDebugPosition(), e);
            rpm = 0;
        }
    }

    private void updateNetwork(KineticOps ops, float cap) {
        if (!ops.livingItem$hasNetwork()) return;
        try {
            ops.livingItem$updateNetwork(cap);
        } catch (NullPointerException e) {
            LOGGER.debug("[StressState] NPE updating network on {} at {}: {}",
                ops.livingItem$getDebugType(), ops.livingItem$getDebugPosition(), e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[StressState] Error updating network on {} at {}",
                ops.livingItem$getDebugType(), ops.livingItem$getDebugPosition(), e);
        }
    }
}
