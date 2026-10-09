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
 *   ACTIVE   ──apply(rpm=0)──→ INACTIVE (detach + clear)
 *   ACTIVE   ──expired───────→ INACTIVE (via tick, detach + clear)
 *   ACTIVE   ──chunkUnload───→ INACTIVE (forced cleanup)
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

    /**
     * 从存档**静默恢复**应力状态（不触发任何网络操作）—— 2026-10-09「彻底方案」。
     *
     * <p>目的：让 {@link #getRPM()} 在 <b>Create 重建动力网之前</b>（即 {@code read()} 阶段）
     * 就返回正确转速 ⇒ 混入的 {@code getGeneratedSpeed()} 一开始就非 0、{@code isSource()} 为真
     * ⇒ Create 的 {@code KineticBlockEntity.read()} 不会把本块转速清 0、邻居转速得以保留 ⇒
     * 容器 tick 的注入变成「<b>无变化</b>」（只 {@code updateNetwork}）⇒ <b>不再走 detach/attach</b>
     * ⇒ 从根上不触发 {@code propagateNewSource} 的销毁分支（见 {@link #applyStress} 注释）。</p>
     *
     * <p>{@code refreshedThisTick = true} 给首个 BE tick 一个宽限：万一容器 tick 未先跑，
     * 也还有 1 tick 余量，随后 {@link #tick} 会照常过期清理（源真的没了不会残留）。</p>
     */
    public void restoreFromDisk(float rpm, float capacity) {
        this.rpm = rpm;
        this.capacity = capacity;
        this.refreshedThisTick = true;
    }

    public void tick(KineticBlockEntity self) {
        if (self.getLevel() == null || self.getLevel().isClientSide) return;

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
                    // ⚠️ 顺序关键：**先设速度、再 detach**（2026-10-09 修「重进存档后小齿轮被销毁」）。
                    //
                    // Create 的 `RotationPropagator.handleRemoved` 在 `getTheoreticalSpeed()==0`
                    // 时【直接 return】。重载后 Create 已把本块 speed 清 0（实测日志 speed=0.0），
                    // 而本块的 `network` 会从 NBT 恢复成「自身坐标」—— 邻居因此仍留在
                    // 「以本块为源的旧网络」里。此时若直接 attachKinetics()：
                    //   propagateNewSource 命中 Create 的「不要压制自己所在的网络（cycle）」判定
                    //   ⇒ `world.destroyBlock(pos, true)` 销毁本块并掉落。
                    // 先设速度 ⇒ handleRemoved 才会真正把邻居从旧网络摘出 ⇒
                    // 随后 attachKinetics 走 overpower 分支（正常接管），不再销毁。
                    self.setSpeed(newRpm);
                    self.detachKinetics();
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