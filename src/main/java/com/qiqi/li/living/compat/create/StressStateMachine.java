package com.qiqi.li.living.compat.create;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 活水车应力输出状态机 —— 管理 KineticBlockEntity 的网络连接与应力状态。
 *
 * <p>从 {@link KineticBlockEntityMixin} 中提取的纯逻辑类，可独立测试。
 * 状态机管理以下生命周期：</p>
 *
 * <pre>
 *   INACTIVE ──apply(rpm≠0)──→ ACTIVE
 *   ACTIVE   ──apply(rpm=0)──→ REATTACHING
 *   ACTIVE   ──expired───────→ REATTACHING (via tick)
 *   REATTACHING ──tick───────→ INACTIVE (reattach neighbors)
 *   REATTACHING ──apply(rpm≠0)──→ ACTIVE (cancel reattach)
 *   ACTIVE   ──chunkUnload───→ INACTIVE (forced cleanup)
 * </pre>
 */
public class StressStateMachine {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/StressState");

    private float rpm;
    private float capacity;
    private boolean refreshedThisTick;
    private boolean pendingReattach;

    public float getRPM() {
        return rpm;
    }

    public float getCapacity() {
        return capacity;
    }

    public boolean isActive() {
        return rpm != 0;
    }

    public void tick(KineticBlockEntity self) {
        if (self.getLevel() == null || self.getLevel().isClientSide) return;

        if (pendingReattach) {
            pendingReattach = false;
            try {
                self.attachKinetics();
                self.setChanged();
                self.sendData();
            } catch (NullPointerException e) {
                LOGGER.debug("[StressState] NPE during delayed reattach on {} at {}: {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("[StressState] Error during delayed reattach on {} at {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e);
            }
        }

        if (rpm == 0) {
            refreshedThisTick = false;
            return;
        }

        if (!refreshedThisTick) {
            try {
                if (self instanceof GeneratingKineticBlockEntity gen) {
                    gen.updateGeneratedRotation();
                } else {
                    refreshedThisTick = true;
                    if (self.hasNetwork()) {
                        try {
                            self.getOrCreateNetwork().remove(self);
                        } catch (Exception e2) {
                            LOGGER.debug("[StressState] Error removing from network on expiry at {}: {}",
                                self.getBlockPos(), e2.getMessage());
                        }
                    }
                    self.detachKinetics();
                    self.setSpeed(0);
                    self.setNetwork(null);
                    self.setChanged();
                    self.sendData();
                }
            } catch (NullPointerException e) {
                LOGGER.debug("[StressState] NPE during expiry cleanup on {} at {}: {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("[StressState] Error during expiry cleanup on {} at {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e);
            }
            rpm = 0;
            capacity = 0;
        }

        refreshedThisTick = false;
    }

    public void onChunkUnloaded(KineticBlockEntity self) {
        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        if (rpm == 0) return;

        refreshedThisTick = true;

        if (self.hasNetwork()) {
            try {
                self.getOrCreateNetwork().remove(self);
            } catch (NullPointerException e) {
                LOGGER.debug("[StressState] NPE removing from network on chunk unload at {}: {}",
                    self.getBlockPos(), e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("[StressState] Error removing from network on chunk unload at {}",
                    self.getBlockPos(), e);
            }
        }

        try {
            self.detachKinetics();
        } catch (NullPointerException e) {
            LOGGER.debug("[StressState] NPE detaching kinetics on chunk unload at {}: {}",
                self.getBlockPos(), e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[StressState] Error detaching kinetics on chunk unload at {}",
                self.getBlockPos(), e);
        }

        rpm = 0;
        capacity = 0;
        refreshedThisTick = false;
        pendingReattach = false;
    }

    public void applyStress(KineticBlockEntity self, float newRpm, float newCap) {
        if (!isSafe(self)) {
            if (rpm != 0) {
                rpm = 0;
                capacity = 0;
            }
            return;
        }

        if (self.getLevel() != null && !self.getLevel().isClientSide) {
            refreshedThisTick = true;
        }

        float prev = rpm;

        if (newRpm != 0) {
            pendingReattach = false;
        }

        if (self.getLevel() == null || self.getLevel().isClientSide) {
            rpm = newRpm;
            capacity = newCap;
            return;
        }
        if (Math.abs(prev - newRpm) < 0.01f) {
            rpm = newRpm;
            if (newRpm != 0) {
                updateNetwork(self, newCap);
            }
            capacity = newCap;
            return;
        }

        try {
            if (prev != 0 && newRpm == 0) {
                if (self.hasNetwork()) {
                    try {
                        self.getOrCreateNetwork().remove(self);
                    } catch (Exception e) {
                        LOGGER.debug("[StressState] Error removing from network on stress clear at {}: {}",
                            self.getBlockPos(), e.getMessage());
                    }
                }
                self.detachKinetics();
                self.setSpeed(0);
                self.setNetwork(null);
                rpm = 0;
                capacity = 0;
            } else {
                rpm = newRpm;
                capacity = newCap;
                if (prev == 0 && newRpm != 0) {
                    self.setSpeed(newRpm);
                    self.setNetwork(self.getBlockPos().asLong());
                    self.attachKinetics();
                    updateNetwork(self, newCap);
                } else {
                    self.detachKinetics();
                    self.setSpeed(newRpm);
                    self.attachKinetics();
                    updateNetwork(self, newCap);
                }
            }
            self.setChanged();
            self.sendData();
        } catch (NullPointerException e) {
            LOGGER.debug("[StressState] NPE applying stress on {} at {}: {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[StressState] Error applying stress on {} at {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e);
            rpm = 0;
        }
    }

    private void updateNetwork(KineticBlockEntity self, float cap) {
        if (!self.hasNetwork()) return;
        try {
            var network = self.getOrCreateNetwork();
            network.updateCapacityFor(self, cap);
            network.updateStressFor(self, self.calculateStressApplied());
            network.updateStress();
        } catch (NullPointerException e) {
            LOGGER.debug("[StressState] NPE updating network on {} at {}: {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[StressState] Error updating network on {} at {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e);
        }
    }

    public static boolean isSafe(KineticBlockEntity self) {
        return self instanceof SimpleKineticBlockEntity
            || self instanceof BracketedKineticBlockEntity;
    }
}