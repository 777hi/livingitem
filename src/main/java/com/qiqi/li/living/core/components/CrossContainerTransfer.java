package com.qiqi.li.living.core.components;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.model.Pos2D;

public final class CrossContainerTransfer {

    private CrossContainerTransfer() {}

    public static boolean execute(ComponentContext ctx,
                                   int stackSize, int maxTransfer) {
        ContainerContext containerCtx = ctx.containerCtx();
        int containerSize = containerCtx.getSize();
        int sourceSlot = ctx.sourceSlot();
        int targetSlot = ctx.targetSlot();

        boolean sourceOutOfBounds = sourceSlot < 0;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (!sourceOutOfBounds && !targetOutOfBounds) return false;

        Level level = containerCtx.getLevel();
        BlockPos containerPos = containerCtx.getBlockPos();
        if (level == null || containerPos == null) return false;

        BlockState blockState = level.getBlockState(containerPos);
        Direction blockFacing = getBlockFacing(blockState);
        if (blockFacing == null) return false;

        if (sourceOutOfBounds && !targetOutOfBounds) {
            return pullFromNeighbor(ctx, containerCtx, level, containerPos,
                                     blockFacing, stackSize, maxTransfer);
        }

        if (targetOutOfBounds && !sourceOutOfBounds) {
            return pushToNeighbor(ctx, containerCtx, level, containerPos,
                                   blockFacing, stackSize, maxTransfer);
        }

        if (sourceOutOfBounds && targetOutOfBounds) {
            return transferBetweenNeighbors(ctx, containerCtx, level, containerPos,
                                             blockFacing, stackSize, maxTransfer);
        }

        return false;
    }

    private static boolean pullFromNeighbor(ComponentContext ctx,
                                             ContainerContext containerCtx, Level level,
                                             BlockPos containerPos, Direction blockFacing,
                                             int stackSize, int maxTransfer) {
        Pos2D sourceOffset = ctx.sourceOffset();
        Direction sourceWorldDir = gridToWorld(sourceOffset, blockFacing);
        if (sourceWorldDir == null) return false;

        Container neighborContainer = getNeighborContainer(level, containerPos, sourceWorldDir);
        if (neighborContainer == null) return false;

        int targetSlot = ctx.targetSlot();
        ItemStack targetStack = containerCtx.getItem(targetSlot);

        for (int i = 0; i < neighborContainer.getContainerSize(); i++) {
            ItemStack sourceStack = neighborContainer.getItem(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

            if (targetStack.isEmpty()) {
                ItemStack toTransfer = sourceStack.copy();
                toTransfer.setCount(transferAmount);
                containerCtx.setItem(targetSlot, toTransfer);

                sourceStack.shrink(transferAmount);
                neighborContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            } else if (targetStack.is(sourceStack.getItem()) &&
                       targetStack.getCount() < targetStack.getMaxStackSize()) {
                int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                int actualTransfer = Math.min(transferAmount, spaceAvailable);

                targetStack.grow(actualTransfer);
                containerCtx.setItem(targetSlot, targetStack);

                sourceStack.shrink(actualTransfer);
                neighborContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            }
        }

        return false;
    }

    private static boolean pushToNeighbor(ComponentContext ctx,
                                           ContainerContext containerCtx, Level level,
                                           BlockPos containerPos, Direction blockFacing,
                                           int stackSize, int maxTransfer) {
        Pos2D targetOffset = ctx.targetOffset();
        Direction targetWorldDir = gridToWorld(targetOffset, blockFacing);
        if (targetWorldDir == null) return false;

        Container neighborContainer = getNeighborContainer(level, containerPos, targetWorldDir);
        if (neighborContainer == null) return false;

        int sourceSlot = ctx.sourceSlot();
        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) return false;

        int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

        Direction insertDir = targetWorldDir.getOpposite();

        for (int i = 0; i < neighborContainer.getContainerSize(); i++) {
            ItemStack targetStack = neighborContainer.getItem(i);

            if (targetStack.isEmpty()) {
                if (neighborContainer instanceof WorldlyContainer wc) {
                    int[] slots = wc.getSlotsForFace(insertDir);
                    boolean canInsert = false;
                    for (int s : slots) {
                        if (s == i) { canInsert = true; break; }
                    }
                    if (!canInsert) continue;
                }

                ItemStack toTransfer = sourceStack.copy();
                toTransfer.setCount(transferAmount);
                neighborContainer.setItem(i, toTransfer);

                sourceStack.shrink(transferAmount);
                containerCtx.setItem(sourceSlot, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            } else if (targetStack.is(sourceStack.getItem()) &&
                       targetStack.getCount() < targetStack.getMaxStackSize()) {
                int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                int actualTransfer = Math.min(transferAmount, spaceAvailable);

                targetStack.grow(actualTransfer);
                neighborContainer.setItem(i, targetStack);

                sourceStack.shrink(actualTransfer);
                containerCtx.setItem(sourceSlot, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            }
        }

        return false;
    }

    private static boolean transferBetweenNeighbors(ComponentContext ctx,
                                                     ContainerContext containerCtx, Level level,
                                                     BlockPos containerPos, Direction blockFacing,
                                                     int stackSize, int maxTransfer) {
        Pos2D sourceOffset = ctx.sourceOffset();
        Pos2D targetOffset = ctx.targetOffset();
        Direction sourceWorldDir = gridToWorld(sourceOffset, blockFacing);
        Direction targetWorldDir = gridToWorld(targetOffset, blockFacing);
        if (sourceWorldDir == null || targetWorldDir == null) return false;

        Container sourceContainer = getNeighborContainer(level, containerPos, sourceWorldDir);
        Container targetContainer = getNeighborContainer(level, containerPos, targetWorldDir);
        if (sourceContainer == null || targetContainer == null) return false;

        for (int i = 0; i < sourceContainer.getContainerSize(); i++) {
            ItemStack sourceStack = sourceContainer.getItem(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

            for (int j = 0; j < targetContainer.getContainerSize(); j++) {
                ItemStack targetStack = targetContainer.getItem(j);

                if (targetStack.isEmpty()) {
                    ItemStack toTransfer = sourceStack.copy();
                    toTransfer.setCount(transferAmount);
                    targetContainer.setItem(j, toTransfer);

                    sourceStack.shrink(transferAmount);
                    sourceContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                    return true;
                } else if (targetStack.is(sourceStack.getItem()) &&
                           targetStack.getCount() < targetStack.getMaxStackSize()) {
                    int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                    int actualTransfer = Math.min(transferAmount, spaceAvailable);

                    targetStack.grow(actualTransfer);
                    targetContainer.setItem(j, targetStack);

                    sourceStack.shrink(actualTransfer);
                    sourceContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                    return true;
                }
            }
        }

        return false;
    }

    public static Direction gridToWorld(Pos2D gridDir, Direction blockFacing) {
        if (gridDir == null || gridDir.isNone()) return null;

        Direction normalized;
        if (gridDir.equals(Pos2D.UP)) {
            normalized = Direction.SOUTH;
        } else if (gridDir.equals(Pos2D.DOWN)) {
            normalized = Direction.NORTH;
        } else if (gridDir.equals(Pos2D.LEFT)) {
            normalized = Direction.EAST;
        } else if (gridDir.equals(Pos2D.RIGHT)) {
            normalized = Direction.WEST;
        } else {
            return null;
        }

        int rotations = getRotationCount(blockFacing);
        for (int i = 0; i < rotations; i++) {
            normalized = normalized.getClockWise(Direction.Axis.Y);
        }

        return normalized;
    }

    public static Pos2D worldToGrid(Direction worldDir, Direction blockFacing) {
        if (worldDir == null || worldDir.getAxis() == Direction.Axis.Y) return Pos2D.NONE;

        int rotations = getRotationCount(blockFacing);
        Direction normalized = worldDir;
        for (int i = 0; i < rotations; i++) {
            normalized = normalized.getCounterClockWise(Direction.Axis.Y);
        }

        return switch (normalized) {
            case NORTH -> Pos2D.DOWN;
            case SOUTH -> Pos2D.UP;
            case WEST  -> Pos2D.RIGHT;
            case EAST  -> Pos2D.LEFT;
            default    -> Pos2D.NONE;
        };
    }

    private static int getRotationCount(Direction blockFacing) {
        return switch (blockFacing) {
            case NORTH -> 0;
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    private static Direction getBlockFacing(BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    private static Container getNeighborContainer(Level level, BlockPos containerPos, Direction direction) {
        BlockPos neighborPos = containerPos.relative(direction);
        BlockEntity neighborBe = level.getBlockEntity(neighborPos);
        if (neighborBe == null) return null;

        if (neighborBe instanceof ChestBlockEntity chest) {
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ChestBlock chestBlock) {
                ChestType chestType = neighborState.getValue(ChestBlock.TYPE);
                if (chestType != ChestType.SINGLE) {
                    return ChestBlock.getContainer(chestBlock, neighborState, level, neighborPos, false);
                }
            }
            return chest;
        }

        if (neighborBe instanceof Container container) {
            return container;
        }

        return null;
    }
}