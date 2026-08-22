package com.qiqi.li.living.mixin;

import com.qiqi.li.living.container.ContainerLivingItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {

    @Inject(method = "getConnectingSide(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Lnet/minecraft/world/level/block/state/properties/RedstoneSide;",
            at = @At("HEAD"), cancellable = true)
    private void onGetConnectingSide(BlockGetter level, BlockPos pos, Direction direction,
            boolean nonNormalCubeAbove, CallbackInfoReturnable<RedstoneSide> cir) {
        if (!(level instanceof Level realLevel)) return;
        if (realLevel.isClientSide) return;

        BlockPos neighborPos = pos.relative(direction);
        if (ContainerLivingItemHandler.getRedstoneDataByPos(realLevel, neighborPos) != null) {
            cir.setReturnValue(RedstoneSide.SIDE);
        }
    }
}