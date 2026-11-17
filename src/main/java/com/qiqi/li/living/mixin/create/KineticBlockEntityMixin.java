package com.qiqi.li.living.mixin.create;

import com.qiqi.li.living.compat.create.KineticOps;
import com.qiqi.li.living.compat.create.LivingItemStressOutput;
import com.qiqi.li.living.compat.create.StressStateMachine;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 将活物品应力状态注入 Create 方块实体，并适配状态机所需的动力网操作。
 *
 * <p>Create 类型判断与 API 调用必须留在本适配层；{@link StressStateMachine} 只依赖
 * {@link KineticOps}。</p>
 */
@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements LivingItemStressOutput, KineticOps {

    @Shadow(remap = false)
    protected float lastCapacityProvided;

    @Shadow(remap = false)
    protected float lastStressApplied;

    private final StressStateMachine stressState = new StressStateMachine();

    /** NBT 键：注入的转速与应力容量，仅用于磁盘存档。 */
    private static final String NBT_RPM = "LivingItemRpm";
    private static final String NBT_CAP = "LivingItemCapacity";

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
        stressState.tick(this);
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
        stressState.applyStress(this, rpm, capacity);
    }

    @Override
    public void livingItem$setGeneratedRPM(float rpm) {
        stressState.applyStress(this, rpm, stressState.getCapacity());
    }

    @Override
    public float livingItem$getGeneratedRPM() {
        return stressState.getRPM();
    }

    @Override
    public void livingItem$setStressCapacity(float capacity) {
        stressState.applyStress(this, stressState.getRPM(), capacity);
    }

    @Override
    public float livingItem$getStressCapacity() {
        return stressState.getCapacity();
    }

    @Override
    public boolean livingItem$isSafeForStressInjection() {
        KineticBlockEntity self = livingItem$self();
        return self instanceof SimpleKineticBlockEntity
            || self instanceof BracketedKineticBlockEntity;
    }

    @Override
    public float livingItem$getTheoreticalSpeed() {
        return livingItem$self().getTheoreticalSpeed();
    }

    @Override
    public void livingItem$onChunkUnloaded() {
        stressState.onChunkUnloaded(this);
    }

    @Override
    public boolean livingItem$isServerSide() {
        KineticBlockEntity self = livingItem$self();
        return self.getLevel() != null && !self.getLevel().isClientSide;
    }

    @Override
    public boolean livingItem$tryUpdateGeneratedRotation() {
        if (livingItem$self() instanceof GeneratingKineticBlockEntity generator) {
            generator.updateGeneratedRotation();
            return true;
        }
        return false;
    }

    @Override
    public boolean livingItem$hasNetwork() {
        return livingItem$self().hasNetwork();
    }

    @Override
    public void livingItem$removeFromNetwork() {
        KineticBlockEntity self = livingItem$self();
        self.getOrCreateNetwork().remove(self);
    }

    @Override
    public void livingItem$detachKinetics() {
        livingItem$self().detachKinetics();
    }

    @Override
    public void livingItem$attachKinetics() {
        livingItem$self().attachKinetics();
    }

    @Override
    public void livingItem$setSpeed(float rpm) {
        livingItem$self().setSpeed(rpm);
    }

    @Override
    public void livingItem$setNetworkToSelf() {
        KineticBlockEntity self = livingItem$self();
        self.setNetwork(self.getBlockPos().asLong());
    }

    @Override
    public void livingItem$clearNetwork() {
        livingItem$self().setNetwork(null);
    }

    @Override
    public void livingItem$updateNetwork(float capacity) {
        KineticBlockEntity self = livingItem$self();
        var network = self.getOrCreateNetwork();
        network.updateCapacityFor(self, capacity);
        network.updateStressFor(self, self.calculateStressApplied());
        network.updateStress();
    }

    @Override
    public void livingItem$setChanged() {
        livingItem$self().setChanged();
    }

    @Override
    public void livingItem$sendData() {
        livingItem$self().sendData();
    }

    @Override
    public String livingItem$getDebugType() {
        return livingItem$self().getClass().getSimpleName();
    }

    @Override
    public String livingItem$getDebugPosition() {
        return String.valueOf(livingItem$self().getBlockPos());
    }

    private KineticBlockEntity livingItem$self() {
        return (KineticBlockEntity) (Object) this;
    }
}
