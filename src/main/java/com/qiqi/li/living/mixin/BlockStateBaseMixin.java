package com.qiqi.li.living.mixin;

import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.domain.hopper.CrossContainerTransfer;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void onGetSignal(BlockGetter level, BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> cir) {
        if (!(level instanceof Level realLevel)) return;
        if (realLevel.isClientSide) return;

        ContainerRedstoneData data = ContainerLivingItemHandler.getRedstoneDataByPos(pos);
        if (data == null) return;

        BlockState state = realLevel.getBlockState(pos);
        if (state == null) return;

        Direction facing = CrossContainerTransfer.getBlockFacing(state);
        if (facing == null) return;

        Pos2D gridDir = CrossContainerTransfer.worldToGrid(direction.getOpposite(), facing);
        if (gridDir == null || gridDir.isNone()) return;

        int internalDir = edgeIndexOf(gridDir);
        int boundarySignal = data.getBoundarySignal(internalDir);
        if (boundarySignal > 0) {
            int vanillaSignal = cir.getReturnValue();
            cir.setReturnValue(Math.max(vanillaSignal, boundarySignal));
        }
    }

    private static int edgeIndexOf(Pos2D dir) {
        if (dir.x() == 0) {
            return dir.y() < 0 ? 0 : 1;
        } else {
            return dir.x() < 0 ? 2 : 3;
        }
    }
}