package com.qiqi.li.living.model;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * 容器 GUI 方向 ⇄ 世界方向 的映射工具（纯几何、无状态）。
 *
 * <p>容器界面的「上/下/左/右」需要按方块的朝向旋转才能对应到世界坐标方向 ——
 * 例如方块朝北时，GUI 上方对应世界南方。</p>
 *
 * <p><b>2026-10-08 从 {@code domain/hopper/CrossContainerTransfer} 上移到 L0 契约层。</b>
 * 这三个方法只依赖 {@link Pos2D} 与原版 {@code Direction} / {@code BlockState}，
 * 却被<b>非 hopper 域</b>使用：{@code domain/redstone/ContainerRedstoneData} 的面信号采样、
 * {@code living/mixin/BlockStateBaseMixin} 的世界信号读取。
 * 留在 hopper 域等于让「redstone / mixin 依赖 hopper」—— 那是<b>放错盒子</b>
 * （同时制造 R3 领域互依赖与 R1 跨模块反向依赖）。</p>
 *
 * <p><b>为什么放 L0 而不是 L1</b>：它是 {@link Pos2D} 这套「容器内 2D 网格」模型的直接配套
 * （GUI 方向 ↔ 世界方向的换算），与 {@code Pos2D} 同包最内聚；且不依赖任何实现。</p>
 */
public final class GridDirections {

    private GridDirections() {
    }

    /** 容器内网格方向 → 世界方向（按方块朝向顺时针旋转）。{@code gridDir} 为空 / NONE 时返回 null。 */
    public static Direction gridToWorld(Pos2D gridDir, Direction blockFacing) {
        if (gridDir == null || gridDir.isNone()) return null;

        Direction normalized;
        if (gridDir.equals(Pos2D.UP))         normalized = Direction.SOUTH;
        else if (gridDir.equals(Pos2D.DOWN))  normalized = Direction.NORTH;
        else if (gridDir.equals(Pos2D.LEFT))  normalized = Direction.EAST;
        else if (gridDir.equals(Pos2D.RIGHT)) normalized = Direction.WEST;
        else return null;

        int rotations = getRotationCount(blockFacing);
        for (int i = 0; i < rotations; i++) {
            normalized = normalized.getClockWise(Direction.Axis.Y);
        }

        return normalized;
    }

    /** 世界方向 → 容器内网格方向（按方块朝向逆旋）。竖直方向（Y 轴）返回 {@link Pos2D#NONE}。 */
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

    /** 方块朝向 → 顺时针旋转次数（NORTH=0 / EAST=1 / SOUTH=2 / WEST=3）。 */
    private static int getRotationCount(Direction blockFacing) {
        return switch (blockFacing) {
            case NORTH -> 0;
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    /** 取方块的朝向属性（{@code FACING} 优先，其次 {@code HORIZONTAL_FACING}）；无朝向属性返回 null。 */
    public static Direction getBlockFacing(BlockState state) {
        for (DirectionProperty prop : new DirectionProperty[]{
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING}) {
            if (state.hasProperty(prop)) {
                return state.getValue(prop);
            }
        }
        return null;
    }
}
