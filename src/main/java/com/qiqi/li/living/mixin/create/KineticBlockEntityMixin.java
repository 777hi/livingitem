package com.qiqi.li.living.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.qiqi.li.living.compat.create.LivingItemStressOutput;
import com.qiqi.li.living.compat.create.StressStateMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements LivingItemStressOutput {

    private final StressStateMachine stressState = new StressStateMachine();

    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$getGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        float rpm = stressState.getRPM();
        if (rpm != 0) {
            cir.setReturnValue(rpm);
        }
    }

    @Inject(method = "onChunkUnloaded", at = @At("HEAD"), remap = false)
    private void livingItem$onChunkUnloaded(CallbackInfo ci) {
        stressState.onChunkUnloaded((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void livingItem$checkExpiry(CallbackInfo ci) {
        stressState.tick((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$calculateAddedStressCapacity(CallbackInfoReturnable<Float> cir) {
        if (stressState.isActive()) {
            cir.setReturnValue(stressState.getCapacity());
        }
    }

    @Override
    public void livingItem$applyStress(float rpm, float capacity) {
        stressState.applyStress((KineticBlockEntity) (Object) this, rpm, capacity);
    }

    @Override
    public void livingItem$setGeneratedRPM(float rpm) {
        stressState.applyStress((KineticBlockEntity) (Object) this, rpm, stressState.getCapacity());
    }

    @Override
    public float livingItem$getGeneratedRPM() {
        return stressState.getRPM();
    }

    @Override
    public void livingItem$setStressCapacity(float capacity) {
        stressState.applyStress((KineticBlockEntity) (Object) this, stressState.getRPM(), capacity);
    }

    @Override
    public float livingItem$getStressCapacity() {
        return stressState.getCapacity();
    }

    @Override
    public boolean livingItem$isSafeForStressInjection() {
        return StressStateMachine.isSafe((KineticBlockEntity) (Object) this);
    }

    @Override
    public float livingItem$getTheoreticalSpeed() {
        return ((KineticBlockEntity) (Object) this).getTheoreticalSpeed();
    }
}