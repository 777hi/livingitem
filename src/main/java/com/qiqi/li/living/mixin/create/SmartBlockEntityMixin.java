package com.qiqi.li.living.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.qiqi.li.living.compat.create.LivingItemStressOutput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Create 的 {@link SmartBlockEntity} 卸载区块时，转交活物品的应力状态清理。
 *
 * <p>拦截 {@code onChunkUnloaded}，当实体同时是 {@link KineticBlockEntity} 且实现了
 * {@link LivingItemStressOutput} 时，调用其 {@code livingItem$onChunkUnloaded}
 * 以释放 {@link StressStateMachine} 中缓存的应力状态。</p>
 */
@Mixin(SmartBlockEntity.class)
public abstract class SmartBlockEntityMixin {

    @Inject(method = "onChunkUnloaded", at = @At("HEAD"), remap = false)
    private void livingItem$onChunkUnloaded(CallbackInfo ci) {
        if ((Object) this instanceof KineticBlockEntity kbe) {
            if (kbe instanceof LivingItemStressOutput stressOutput) {
                stressOutput.livingItem$onChunkUnloaded();
            }
        }
    }
}