package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerDrops;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * A6（2026-10-27 定案）：双箱拆半 ⇒ 物品照原版分家，容器级状态不分摊不复制、重新开始。
 *
 * <p><b>被守的行为</b>：原版箱子不是 contentRetaining ⇒ 拆半时流体 moveOut 早退，
 * 存活半的附件残留会被新键的「附件回填」复活成旧源 ⇒ 拆半时显式清两半附件。
 * 拆半判定下沉在 {@link ContainerDrops#otherChestHalf}（L1，不碰领域类型）。</p>
 */
class ContainerDataMigrationChestSplitTest {

    /** 指定箱型（左/右/单）的箱子 BE 替身，附件持给定流体数据。 */
    private static BlockEntity chestOf(net.minecraft.world.level.block.state.properties.ChestType type,
                                       ContainerFluidData data) {
        BlockEntity be = mock(BlockEntity.class);
        BlockState state = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, type);
        when(be.getBlockState()).thenReturn(state);
        when(be.getData(WaterComponents.CONTAINER_FLUID_DATA)).thenReturn(data);
        return be;
    }

    private static ContainerFluidData dataWithWater() {
        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        return data;
    }

    @Test
    @DisplayName("otherChestHalf：双箱左半 ⇒ 返回右半位置（东）")
    void otherHalf_leftBreaks_returnsRight() {
        BlockEntity left = chestOf(
            net.minecraft.world.level.block.state.properties.ChestType.LEFT, dataWithWater());
        BlockPos pos = new BlockPos(10, 64, 20);
        when(left.getBlockPos()).thenReturn(pos);

        BlockPos other = ContainerDrops.otherChestHalf(left);

        assertNotNull(other);
        assertEquals(pos.relative(Direction.EAST), other, "左半的另一半在东侧");
    }

    @Test
    @DisplayName("otherChestHalf：单箱 / 非箱子 ⇒ null（不误触发拆半清空）")
    void singleChestOrNonChest_returnsNull() {
        BlockEntity single = chestOf(
            net.minecraft.world.level.block.state.properties.ChestType.SINGLE, dataWithWater());
        assertNull(ContainerDrops.otherChestHalf(single), "单箱没有另一半");

        BlockEntity furnace = mock(BlockEntity.class);
        when(furnace.getBlockState()).thenReturn(Blocks.FURNACE.defaultBlockState());
        assertNull(ContainerDrops.otherChestHalf(furnace), "非箱子不触发拆半");
    }

    @Test
    @DisplayName("拆半清空：存活半流体附件被置为 EMPTY（旧源不得经附件回填复活；被破坏半随 BE 移除）")
    void split_clearsSurvivingHalfAttachment() {
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
        when(surviving.getData(WaterComponents.CONTAINER_FLUID_DATA)).thenReturn(dataWithWater());
        BlockPos rightPos = new BlockPos(11, 64, 20);
        when(level.getBlockEntity(rightPos)).thenReturn(surviving);

        ContainerDataMigration.clearFluidOnChestSplit(level, broken);

        // 存活半：附件清空 —— 否则新键的附件回填把拆半前的旧源带回来
        verify(surviving).setData(org.mockito.Mockito.eq(WaterComponents.CONTAINER_FLUID_DATA.value()),
            org.mockito.Mockito.eq(ContainerFluidData.EMPTY));
    }
}
