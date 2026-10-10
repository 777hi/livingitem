package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * crash-2026-10-10 回归守卫。
 *
 * <p>背景：{@link LivingItem#runtimeContainerInstances(Level, BlockPos)} 按容器键位置取方块实体后，
 * 历史上无条件 {@code (Container) be} 强转。某些模组容器（如 ProjectE 炼金箱
 * {@code AlchBlockEntityChest}）暴露 {@code IItemHandler} 能力、被当作活物品容器 tick，
 * 但其方块实体<b>不实现原版 {@link Container}</b> ⇒ 运行时同步阶段抛 {@code ClassCastException}。</p>
 *
 * <p>修复后：非 {@link Container} 的方块实体应返回空集合（= 不发包），且不抛异常。
 * 本测试直接驱动拆出的单 level 分支（静态方法），避免拉起 {@code ServerLifecycleHooks}。</p>
 */
class LivingItemRuntimeContainerInstancesTest {

    private static final BlockPos POS = new BlockPos(1, 64, 2);

    /**
     * 一个「该位置非大箱子」的 level 替身：{@code DoubleChestPositions.find} 依赖
     * {@code getBlockState().getBlock() instanceof ChestBlock}，返回非 ChestBlock 即可让其返回空列表，
     * 从而走「单 BE」分支（被测逻辑所在分支）。
     */
    private static Level singleBeLevel() {
        Level level = Mockito.mock(Level.class);
        BlockState state = Mockito.mock(BlockState.class);
        Mockito.when(state.getBlock()).thenReturn(Mockito.mock(Block.class)); // 非 ChestBlock
        Mockito.when(level.getBlockState(POS)).thenReturn(state);
        return level;
    }

    @Test
    @DisplayName("非 Container 方块实体 → 返回空集合且不抛异常（crash-2026-10-10 核心回归）")
    void nonContainerBlockEntityReturnsEmpty() {
        Level level = singleBeLevel();
        // 纯 BlockEntity 替身，未实现 Container（正是 ProjectE 炼金箱的情形）
        BlockEntity be = Mockito.mock(BlockEntity.class);
        Mockito.when(level.getBlockEntity(POS)).thenReturn(be);

        var result = LivingItem.runtimeContainerInstances(level, POS);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "非 Container 的 BE 应返回空集合（= 不发包），而非抛 ClassCastException");
    }

    @Test
    @DisplayName("原版 Container 方块实体 → 返回包含该容器的单元素集合")
    void containerBlockEntityReturnsItself() {
        Level level = singleBeLevel();
        // 同时是 BlockEntity 与 Container 的替身（原版箱子 / 大箱半箱的情形）
        BlockEntity be = Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
        Mockito.when(level.getBlockEntity(POS)).thenReturn(be);

        var result = LivingItem.runtimeContainerInstances(level, POS);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(be));
    }

    @Test
    @DisplayName("位置无方块实体 → 返回 null（让调用方继续查下一个世界）")
    void noBlockEntityReturnsNull() {
        Level level = singleBeLevel();
        Mockito.when(level.getBlockEntity(POS)).thenReturn(null);

        var result = LivingItem.runtimeContainerInstances(level, POS);

        assertNull(result);
    }
}
