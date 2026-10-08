package com.qiqi.li.living.mixin;

import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.model.GridDirections;
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

/**
 * 把活红石账本（{@link ContainerRedstoneData}）的信号接入原版红石传播。
 *
 * <p>拦截 {@code getSignal}，当方块位置对应容器存在边界出边信号时，
 * 取其与 vanilla 信号的最大值返回，使活红石能向相邻方块供电。</p>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void onGetSignal(BlockGetter level, BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> cir) {
        if (!(level instanceof Level realLevel)) return;
        if (realLevel.isClientSide) return;

        ContainerRedstoneData data = ContainerLivingItemHandler.getRedstoneDataByPos(realLevel, pos);
        if (data == null) return;

        BlockState state = realLevel.getBlockState(pos);
        if (state == null) return;

        Direction facing = GridDirections.getBlockFacing(state);
        if (facing == null) return;

        Pos2D gridDir = GridDirections.worldToGrid(direction.getOpposite(), facing);
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