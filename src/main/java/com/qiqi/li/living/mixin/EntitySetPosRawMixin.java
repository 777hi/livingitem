package com.qiqi.li.living.mixin;

import com.qiqi.li.living.domain.map.TeleportHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntitySetPosRawMixin {

    @Shadow
    private Vec3 position;

    @Shadow
    private BlockPos blockPosition;

    @Shadow
    private ChunkPos chunkPosition;

    @Shadow
    private BlockState inBlockState;

    @Shadow
    private EntityInLevelCallback levelCallback;

    @Shadow
    public abstract void setBoundingBox(AABB bb);

    @Shadow
    public abstract AABB makeBoundingBox();

    @Inject(method = "setPos(DDD)V", at = @At("HEAD"), cancellable = true)
    private void onSetPos(double x, double y, double z, CallbackInfo ci) {
        if (!TeleportHelper.isBypassingChunkLoad()) {
            return;
        }

        if (this.position.x != x || this.position.y != y || this.position.z != z) {
            this.position = new Vec3(x, y, z);
            int i = Mth.floor(x);
            int j = Mth.floor(y);
            int k = Mth.floor(z);
            if (i != this.blockPosition.getX() || j != this.blockPosition.getY() || k != this.blockPosition.getZ()) {
                this.blockPosition = new BlockPos(i, j, k);
                this.inBlockState = null;
                if (SectionPos.blockToSectionCoord(i) != this.chunkPosition.x
                    || SectionPos.blockToSectionCoord(k) != this.chunkPosition.z) {
                    this.chunkPosition = new ChunkPos(this.blockPosition);
                }
            }
            this.levelCallback.onMove();
        }

        this.setBoundingBox(this.makeBoundingBox());
        ci.cancel();
    }
}