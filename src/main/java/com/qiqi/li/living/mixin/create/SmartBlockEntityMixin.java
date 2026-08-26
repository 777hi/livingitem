package com.qiqi.li.living.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.qiqi.li.living.compat.create.LivingItemStressOutput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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