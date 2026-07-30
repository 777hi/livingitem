package com.qiqi.li.living.mixin.create;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import com.qiqi.li.living.compat.create.LivingItemStressOutput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements LivingItemStressOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Mixin");

    private float livingItem$generatedRPM = 0;
    private float livingItem$stressCapacity = 0;
    private boolean livingItem$refreshedThisTick = false;
    private boolean livingItem$pendingReattach = false;

    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$getGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        if (livingItem$generatedRPM != 0) {
            if (!livingItem$refreshedThisTick) {
                livingItem$generatedRPM = 0;
                livingItem$stressCapacity = 0;
                return;
            }
            cir.setReturnValue(livingItem$generatedRPM);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void livingItem$checkExpiry(CallbackInfo ci) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide) return;

        if (livingItem$pendingReattach) {
            livingItem$pendingReattach = false;
            try {
                self.attachKinetics();
                self.setChanged();
                self.sendData();
            } catch (Exception e) {
                LOGGER.warn("[LivingItem] Error during delayed reattach on {} at {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e);
            }
        }

        if (livingItem$generatedRPM == 0) {
            livingItem$refreshedThisTick = false;
            return;
        }

        if (!livingItem$refreshedThisTick) {
            livingItem$generatedRPM = 0;
            livingItem$stressCapacity = 0;

            try {
                if (self instanceof GeneratingKineticBlockEntity gen) {
                    gen.updateGeneratedRotation();
                } else {
                    self.detachKinetics();
                    self.setSpeed(0);
                    self.setNetwork(null);
                    livingItem$pendingReattach = true;
                    self.setChanged();
                    self.sendData();
                }
            } catch (Exception e) {
                LOGGER.warn("[LivingItem] Error during expiry cleanup on {} at {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e);
            }
        }

        livingItem$refreshedThisTick = false;
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$calculateAddedStressCapacity(CallbackInfoReturnable<Float> cir) {
        if (livingItem$generatedRPM != 0) {
            cir.setReturnValue(livingItem$stressCapacity);
        }
    }

    @Override
    public void livingItem$setGeneratedRPM(float rpm) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (!livingItem$isSafeForStressInjection()) {
            if (livingItem$generatedRPM != 0) {
                livingItem$generatedRPM = 0;
                livingItem$stressCapacity = 0;
            }
            return;
        }

        if (self.getLevel() != null && !self.getLevel().isClientSide) {
            livingItem$refreshedThisTick = true;
        }

        float prev = livingItem$generatedRPM;
        livingItem$generatedRPM = rpm;

        if (rpm != 0) {
            livingItem$pendingReattach = false;
        }

        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        if (Math.abs(prev - rpm) < 0.01f) return;

        try {
            if (prev != 0 && rpm == 0) {
                self.detachKinetics();
                self.setSpeed(0);
                self.setNetwork(null);
                livingItem$pendingReattach = true;
            } else if (prev == 0 && rpm != 0) {
                self.setSpeed(rpm);
                self.setNetwork(self.getBlockPos().asLong());
                self.attachKinetics();
                if (self.hasNetwork()) {
                    self.getOrCreateNetwork().updateCapacityFor(self, livingItem$stressCapacity);
                    self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
                    self.getOrCreateNetwork().updateStress();
                }
            } else {
                self.detachKinetics();
                self.setSpeed(rpm);
                self.attachKinetics();
                if (self.hasNetwork()) {
                    self.getOrCreateNetwork().updateCapacityFor(self, livingItem$stressCapacity);
                    self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
                    self.getOrCreateNetwork().updateStress();
                }
            }
            self.setChanged();
            self.sendData();
        } catch (NullPointerException e) {
            LOGGER.warn("[LivingItem] Failed to set RPM on {} at {}: {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e.getMessage());
            livingItem$generatedRPM = 0;
        } catch (Exception e) {
            LOGGER.warn("[LivingItem] Unexpected error setting RPM on {} at {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e);
            livingItem$generatedRPM = 0;
        }
    }

    @Override
    public float livingItem$getGeneratedRPM() {
        return livingItem$generatedRPM;
    }

    @Override
    public void livingItem$setStressCapacity(float capacity) {
        float prev = livingItem$stressCapacity;
        livingItem$stressCapacity = capacity;

        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        if (Math.abs(prev - capacity) < 0.01f) return;
        if (livingItem$generatedRPM == 0) return;
        if (!self.hasNetwork()) return;

        try {
            self.getOrCreateNetwork().updateCapacityFor(self, capacity);
            self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
            self.getOrCreateNetwork().updateStress();
        } catch (Exception e) {
            LOGGER.warn("[LivingItem] Error updating stress capacity on {} at {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e);
        }
    }

    @Override
    public float livingItem$getStressCapacity() {
        return livingItem$stressCapacity;
    }

    @Override
    public boolean livingItem$isSafeForStressInjection() {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        String className = self.getClass().getName();
        if (className.startsWith("com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity")) {
            return true;
        }
        if (className.startsWith("com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity")) {
            return true;
        }
        return false;
    }

    @Override
    public float livingItem$getTheoreticalSpeed() {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        return self.getTheoreticalSpeed();
    }
}