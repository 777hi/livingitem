package com.qiqi.li.living.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.List;

/**
 * 大箱子双半箱位置查找工具。
 * <p>
 * 大箱子由两个方块组成（LEFT + RIGHT），此工具返回有序列表 [左半箱, 右半箱]，
 * 用于跨容器传输和容器扫描中的双箱合并处理。
 * </p>
 * <p>
 * 提取自 {@code ContainerLivingItemHandler.findDoubleChestPositions} 与
 * {@code CrossContainerTransfer.findDoubleChestPositions} 的重复实现，
 * 统一归口至此工具类。
 * </p>
 */
public final class DoubleChestPositions {

    private DoubleChestPositions() {}

    /**
     * 查找大箱子的两个半箱位置。
     *
     * @param level 世界
     * @param pos   任意一个半箱的位置
     * @return 有序列表 [左半箱, 右半箱]；若非大箱子则返回空列表
     */
    public static List<BlockPos> find(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return List.of();

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) return List.of();

        Direction connectedDir = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connectedDir);

        if (chestType == ChestType.LEFT) {
            return List.of(pos, otherPos);
        } else {
            return List.of(otherPos, pos);
        }
    }
}