package com.qiqi.li.living.domain.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.qiqi.li.living.container.ContainerLivingItemHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 边界外部输入采样的<b>自反馈守卫</b>（2026-10-27 审查 P1）。
 *
 * <p><b>被守的行为</b>：双箱（大箱两半）共用同一份 {@link ContainerRedstoneData}，
 * {@code injectExternalInputs} 朝另一半的方向会查到 {@code this} —— 曾把自己对面 face
 * 的输出读回对侧 face 的输入 ⇒ 信号隔空「穿越」容器（东边拉杆压灭西边火把，无任何线路）。
 * 修复：邻居直读只对<b>别的容器</b>成立，邻居是自己时保持世界信号原样。</p>
 */
class ContainerRedstoneBoundaryTest {

    private static final BlockPos NEIGHBOR = new BlockPos(1, 64, 1);

    private final Level level = mock(Level.class);

    @AfterEach
    void tearDown() {
        // mockStatic 是 try-with-resources 管理的，这里无需额外清理
    }

    @Test
    @DisplayName("邻居账本是自己（双箱另一半）⇒ 保持世界信号，不读自己的边界输出")
    void selfNeighbor_worldSignalOnly() {
        ContainerRedstoneData self = mock(ContainerRedstoneData.class);
        when(self.getBoundarySignal(anyInt())).thenReturn(99);   // 自己的边界输出若被读回即污染

        try (MockedStatic<ContainerLivingItemHandler> mocked =
                 mockStatic(ContainerLivingItemHandler.class)) {
            mocked.when(() -> ContainerLivingItemHandler.peekContainerDataByPos(
                    any(), any(), any())).thenReturn(self);

            int out = ContainerRedstoneData.withNeighborBoundary(
                level, NEIGHBOR, net.minecraft.core.Direction.EAST, 5, self);

            assertEquals(5, out,
                "自反馈 ⇒ 东边拉杆(99)会隔空压灭西边火把 —— 邻居是自己时必须跳过");
        }
    }

    @Test
    @DisplayName("邻居无账本 ⇒ 世界信号原样")
    void noNeighborLedger_worldSignalOnly() {
        try (MockedStatic<ContainerLivingItemHandler> mocked =
                 mockStatic(ContainerLivingItemHandler.class)) {
            mocked.when(() -> ContainerLivingItemHandler.peekContainerDataByPos(
                    any(), any(), any())).thenReturn(null);

            int out = ContainerRedstoneData.withNeighborBoundary(
                level, NEIGHBOR, net.minecraft.core.Direction.EAST, 5,
                mock(ContainerRedstoneData.class));

            assertEquals(5, out);
        }
    }

    @Test
    @DisplayName("邻居是别的容器但其方块无朝向 ⇒ 世界信号原样（既有兜底，防重构漂移）")
    void otherNeighborWithoutFacing_worldSignalOnly() {
        ContainerRedstoneData other = mock(ContainerRedstoneData.class);
        when(other.getBoundarySignal(anyInt())).thenReturn(7);
        // 石头无 FACING 属性（生产里 getBlockState 恒非 null，这里用真实无朝向方块）
        when(level.getBlockState(NEIGHBOR)).thenReturn(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());

        try (MockedStatic<ContainerLivingItemHandler> mocked =
                 mockStatic(ContainerLivingItemHandler.class)) {
            mocked.when(() -> ContainerLivingItemHandler.peekContainerDataByPos(
                    any(), any(), any())).thenReturn(other);

            int out = ContainerRedstoneData.withNeighborBoundary(
                level, NEIGHBOR, net.minecraft.core.Direction.EAST, 5,
                mock(ContainerRedstoneData.class));

            assertEquals(5, out);
        }
    }
}
