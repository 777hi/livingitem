package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * crash-2026-10-10 回归守卫 + 缓存键反查回归守卫（2026-10-12）。
 *
 * <p>背景一：{@link LivingItem#runtimeContainerInstances(Level, BlockPos)} 按容器键位置取方块实体后，
 * 历史上无条件 {@code (Container) be} 强转。某些模组容器（如 ProjectE 炼金箱
 * {@code AlchBlockEntityChest}）暴露 {@code IItemHandler} 能力、被当作活物品容器 tick，
 * 但其方块实体<b>不实现原版 {@link Container}</b> ⇒ 运行时同步阶段抛 {@code ClassCastException}。</p>
 *
 * <p>背景二（本次修复）：缓存键加维度前缀（{@code <维度>|chest_…}）后，按裸键格式解析坐标的
 * {@code parsePosFromContainerKey} 没跟着改 ⇒ 对前缀键返回 null ⇒ {@code runtimeContainerInstances}
 * 恒为空 ⇒ **所有世界容器的 tooltip 遥测包停发**（用户实测：活熔炉进度不滚动、熔炼功能正常
 * —— 功能读写同用一把键，只有发包反查按旧格式解析）。现在按「有前缀 ⇒ 定向该维度」处理。</p>
 *
 * <p>修复后：非 {@link Container} 的方块实体应返回空集合（= 不发包），且不抛异常；
 * 前缀键命中声明维度的容器、绝不越维度找补。本测试直接驱动拆出的静态方法，避免拉起
 * {@code ServerLifecycleHooks}。</p>
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

    // ── 缓存键反查（2026-10-12 回归：tooltip 进度不滚动）───────────

    /**
     * 带维度 id 的 ServerLevel 替身；{@code be} 为该位置 POS 上的方块实体（null = 没有）。
     */
    private static ServerLevel serverLevel(ResourceKey<Level> dim, BlockEntity be) {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.dimension()).thenReturn(dim);
        BlockState state = Mockito.mock(BlockState.class);
        Mockito.when(state.getBlock()).thenReturn(Mockito.mock(Block.class)); // 非 ChestBlock ⇒ 单 BE 分支
        Mockito.when(level.getBlockState(POS)).thenReturn(state);
        Mockito.when(level.getBlockEntity(POS)).thenReturn(be);
        return level;
    }

    private static BlockEntity containerBe() {
        return Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
    }

    @Test
    @DisplayName("缓存键带维度前缀 → 命中该维度的容器（回归核心：前缀键此前解析失败 ⇒ 全世界容器 tooltip 停更）")
    void prefixedKey_targetsItsDimension() {
        BlockEntity overworldBe = containerBe();
        BlockEntity netherBe = containerBe();
        List<ServerLevel> levels = List.of(
            serverLevel(Level.OVERWORLD, overworldBe),
            serverLevel(Level.NETHER, netherBe));

        var result = LivingItem.runtimeContainerInstances(levels, "minecraft:overworld|chest_1_64_2");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(overworldBe), "应按键里的维度命中主世界那个箱子");
        assertFalse(result.contains(netherBe), "不能把下界同坐标的箱子卷进来");
    }

    @Test
    @DisplayName("前缀声明的主世界该位置无 BE → 空集合（不越维度找补，宁可不发也不错发）")
    void prefixedKey_noBeInDeclaredDimension_doesNotFallThrough() {
        BlockEntity netherBe = containerBe();
        List<ServerLevel> levels = List.of(
            serverLevel(Level.OVERWORLD, null),          // 主世界该位置没有方块实体
            serverLevel(Level.NETHER, netherBe));

        var result = LivingItem.runtimeContainerInstances(levels, "minecraft:overworld|chest_1_64_2");

        assertNotNull(result);
        assertTrue(result.isEmpty(), "键声明主世界 ⇒ 主世界没 BE 就是没容器，不许拿下界的顶包");
    }

    @Test
    @DisplayName("前缀声明的维度不在候选列表 → 空集合")
    void prefixedKey_unknownDimension_returnsEmpty() {
        List<ServerLevel> levels = List.of(serverLevel(Level.NETHER, containerBe()));

        assertTrue(LivingItem.runtimeContainerInstances(levels, "minecraft:overworld|chest_1_64_2").isEmpty());
    }

    @Test
    @DisplayName("裸键（无维度前缀，退化形态/测试替身）→ 扫描全部世界的首个命中（旧行为保持）")
    void rawKey_scansAllLevels() {
        BlockEntity netherBe = containerBe();
        List<ServerLevel> levels = List.of(
            serverLevel(Level.OVERWORLD, null),          // 主世界没有 ⇒ 继续查下一个世界
            serverLevel(Level.NETHER, netherBe));

        var result = LivingItem.runtimeContainerInstances(levels, "chest_1_64_2");

        assertEquals(1, result.size());
        assertTrue(result.contains(netherBe));
    }

    @Test
    @DisplayName("背包键 / 坏键 → 空集合（背包分支在 LivingItemRuntimeSync 另走，解析不出坐标即安全跳过）")
    void playerAndMalformedKeys_returnEmpty() {
        List<ServerLevel> levels = List.of(serverLevel(Level.OVERWORLD, containerBe()));

        assertTrue(LivingItem.runtimeContainerInstances(levels, "player_550e8400-e29b-41d4-a716-446655440000").isEmpty());
        assertTrue(LivingItem.runtimeContainerInstances(levels, "minecraft:overworld|not_a_chest_key").isEmpty());
        assertTrue(LivingItem.runtimeContainerInstances(levels, null).isEmpty());
    }

    @Test
    @DisplayName("dimPrefixOf：带前缀取维度、裸键/背包键取 null")
    void dimPrefixOf_parsesPrefixOnly() {
        assertEquals("minecraft:overworld", LivingItem.dimPrefixOf("minecraft:overworld|chest_1_64_2"));
        assertNull(LivingItem.dimPrefixOf("chest_1_64_2"));
        assertNull(LivingItem.dimPrefixOf("player_x"));
        assertNull(LivingItem.dimPrefixOf(null));
    }
}
