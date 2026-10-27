package com.qiqi.li.living.domain.power;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerDrops;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A6（2026-10-27 定案）：双箱拆半 ⇒ 红电相位不分摊不复制、按新布局重新识别信号。
 *
 * <p><b>被守的行为</b>：存活半的相位快照附件若不清，会经「回填」类路径把拆半前的
 * 锁相带回新结构（流体同源问题已在 water 域修）。被破坏半的 BE 随方块移除、
 * 附件随之消失，无需处理。</p>
 */
class PhaseDataMigrationChestSplitTest {

    private static BlockEntity chestOf(net.minecraft.world.level.block.state.properties.ChestType type,
                                       PhaseSnapshot snapshot) {
        BlockEntity be = mock(BlockEntity.class);
        BlockState state = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, type);
        when(be.getBlockState()).thenReturn(state);
        when(be.getData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value())).thenReturn(snapshot);
        return be;
    }

    @Test
    @DisplayName("拆半清空：存活半相位快照附件被置为 EMPTY（旧锁相不得经回填复活）")
    void split_clearsSurvivingHalfPhase() {
        ServerLevel level = mock(ServerLevel.class);
        var LEFT = net.minecraft.world.level.block.state.properties.ChestType.LEFT;
        var RIGHT = net.minecraft.world.level.block.state.properties.ChestType.RIGHT;

        BlockState leftState = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, LEFT);
        BlockState rightState = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, RIGHT);

        BlockEntity broken = mock(BlockEntity.class);      // 被破坏的左半
        when(broken.getBlockState()).thenReturn(leftState);
        when(broken.getBlockPos()).thenReturn(new BlockPos(10, 64, 20));

        BlockEntity surviving = mock(BlockEntity.class);   // 存活的右半
        when(surviving.getBlockState()).thenReturn(rightState);
        var stale = new PhaseSnapshot(1000L, java.util.List.of(
            new PhaseSnapshot.EdgeEntry(42L, 8, 3, 5, 4096)));
        when(surviving.getData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value())).thenReturn(stale);
        BlockPos rightPos = new BlockPos(11, 64, 20);
        when(level.getBlockEntity(rightPos)).thenReturn(surviving);

        PhaseDataMigration.clearPhaseOnChestSplit(level, broken);

        verify(surviving).setData(
            org.mockito.Mockito.eq(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value()),
            org.mockito.Mockito.eq(PhaseSnapshot.EMPTY));
    }

    @Test
    @DisplayName("单箱 / 非箱子 ⇒ 不触发拆半清空")
    void singleChestOrNonChest_noClear() {
        ServerLevel level = mock(ServerLevel.class);
        BlockEntity single = chestOf(
            net.minecraft.world.level.block.state.properties.ChestType.SINGLE, PhaseSnapshot.EMPTY);

        PhaseDataMigration.clearPhaseOnChestSplit(level, single);

        org.mockito.Mockito.verifyNoInteractions(level);
    }
}
