package com.qiqi.li.living.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.qiqi.li.living.domain.map.TeleportHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntitySetPosRawMixin {

    @WrapOperation(
        method = "setPosRaw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;"
        )
    )
    private ChunkAccess wrapGetChunkInSetPosRaw(Level level, int x, int z, Operation<ChunkAccess> original) {
        if (TeleportHelper.isBypassingChunkLoad()) {
            return null;
        }
        return original.call(level, x, z);
    }
}