package com.qiqi.li.living.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.qiqi.li.living.compat.create.LivingItemStressOutput;
import com.qiqi.li.living.compat.create.StressStateMachine;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 将活物品产生的应力注入 Create 的 {@link KineticBlockEntity}。
 *
 * <p>实现 {@link LivingItemStressOutput}，用 {@link StressStateMachine} 维护 RPM 与应力容量，
 * 并在 {@code getGeneratedSpeed} / {@code isSource} / {@code calculateAddedStressCapacity} /
 * {@code calculateStressApplied} 等处覆盖 Create 的默认值，使活物品表现为动力源。</p>
 */
@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements LivingItemStressOutput {

    @Shadow(remap = false)
    protected float lastCapacityProvided;

    @Shadow(remap = false)
    protected float lastStressApplied;

    private final StressStateMachine stressState = new StressStateMachine();

    /** NBT 键：注入的转速 / 应力容量（仅磁盘，2026-10-09「彻底方案」）。 */
    private static final String NBT_RPM = "LivingItemRpm";
    private static final String NBT_CAP = "LivingItemCapacity";

    /**
     * 落盘：把当前注入的转速/容量写进 BE 的 NBT（<b>仅磁盘保存</b>，不改网络包）。
     *
     * <p>配合 {@link StressStateMachine#restoreFromDisk} —— 让重载后 {@code getGeneratedSpeed()}
     * 立即正确，Create 就不会把本块转速清 0，我们也不必重新 {@code attachKinetics()}
     * （那会触发 {@code propagateNewSource} 的销毁分支，见 2026-10-09 修复）。</p>
     */
    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), remap = false)
    private void livingItem$writeStress(CompoundTag compound, HolderLookup.Provider registries,
                                        boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        float rpm = stressState.getRPM();
        if (rpm != 0) {
            compound.putFloat(NBT_RPM, rpm);
            compound.putFloat(NBT_CAP, stressState.getCapacity());
        }
    }

    /** 读盘：恢复转速/容量（<b>仅磁盘加载</b>，不碰网络包）。 */
    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), remap = false)
    private void livingItem$readStress(CompoundTag compound, HolderLookup.Provider registries,
                                       boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        if (compound.contains(NBT_RPM)) {
            stressState.restoreFromDisk(compound.getFloat(NBT_RPM), compound.getFloat(NBT_CAP));
        }
    }

    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$getGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        float rpm = stressState.getRPM();
        if (rpm != 0) {
            cir.setReturnValue(rpm);
        }
    }

    @Inject(method = "isSource", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$isSource(CallbackInfoReturnable<Boolean> cir) {
        float rpm = stressState.getRPM();
        if (rpm != 0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void livingItem$checkExpiry(CallbackInfo ci) {
        stressState.tick((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$calculateAddedStressCapacity(CallbackInfoReturnable<Float> cir) {
        if (stressState.isActive()) {
            float cap = stressState.getCapacity();
            lastCapacityProvided = cap;
            cir.setReturnValue(cap);
        }
    }

    @Inject(method = "calculateStressApplied", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$calculateStressApplied(CallbackInfoReturnable<Float> cir) {
        if (stressState.isActive()) {
            lastStressApplied = 0f;
            cir.setReturnValue(0f);
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

    @Override
    public void livingItem$onChunkUnloaded() {
        stressState.onChunkUnloaded((KineticBlockEntity) (Object) this);
    }
}