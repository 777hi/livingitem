package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 运行时遥测发包的「容器键 → 位置」反查守卫。
 *
 * <p>演进（三次口径变化，别按旧结论读）：</p>
 * <ol>
 *   <li><b>crash-2026-10-10</b>：曾无条件 {@code (Container) be} 强转，对 ProjectE 炼金箱这类
 *       「有物品能力但不是原版 {@link Container}」的方块实体抛 {@code ClassCastException}。</li>
 *   <li><b>2026-10-12</b>：缓存键加维度前缀后坐标解析没跟着改 ⇒ 前缀键返回 null
 *       ⇒ <b>所有世界容器的 tooltip 遥测包停发</b>（功能正常、进度不滚动）。</li>
 *   <li><b>2026-10-18（专题 S3，契约 I-C5）</b>：改为返回<b>位置</b>而不是 {@code Container}
 *       实例，并且<b>不再要求 {@code be instanceof Container}</b> —— 非 {@code Container} 的
 *       模组容器也必须能匹配 viewer。真机表现：「精妙背包里的活熔炉 tooltip 没有进度信息」。</li>
 * </ol>
 *
 * <p>本测试直接驱动拆出的静态方法，避免拉起 {@code ServerLifecycleHooks}。</p>
 */
class LivingItemRuntimeContainerPositionsTest {

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
    @DisplayName("非 Container 方块实体 → 返回位置（I-C5：不再返回空集合，否则 viewer 永远匹配不上）")
    void nonContainerBlockEntityReturnsPosition() {
        Level level = singleBeLevel();
        // 纯 BlockEntity 替身，未实现 Container（正是精妙背包方块 / ProjectE 炼金箱的情形）
        BlockEntity be = Mockito.mock(BlockEntity.class);
        Mockito.when(level.getBlockEntity(POS)).thenReturn(be);

        var result = LivingItem.runtimeContainerPositions(level, POS);

        assertNotNull(result);
        assertEquals(List.of(POS), List.copyOf(result),
            "非 Container 的 BE 也必须返回位置 —— 否则运行时 tooltip 遥测永远不发");
    }

    @Test
    @DisplayName("原版 Container 方块实体 → 返回该位置")
    void containerBlockEntityReturnsPosition() {
        Level level = singleBeLevel();
        BlockEntity be = Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
        Mockito.when(level.getBlockEntity(POS)).thenReturn(be);

        var result = LivingItem.runtimeContainerPositions(level, POS);

        assertNotNull(result);
        assertEquals(List.of(POS), List.copyOf(result));
    }

    @Test
    @DisplayName("位置无方块实体 → 返回 null（让调用方继续查下一个世界）")
    void noBlockEntityReturnsNull() {
        Level level = singleBeLevel();
        Mockito.when(level.getBlockEntity(POS)).thenReturn(null);

        assertNull(LivingItem.runtimeContainerPositions(level, POS));
    }

    // ── 缓存键反查（2026-10-12 回归：tooltip 进度不滚动）───────────

    /** 带维度 id 的 ServerLevel 替身；{@code be} 为该位置 POS 上的方块实体（null = 没有）。 */
    private static ServerLevel serverLevel(ResourceKey<Level> dim, BlockEntity be) {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.dimension()).thenReturn(dim);
        BlockState state = Mockito.mock(BlockState.class);
        Mockito.when(state.getBlock()).thenReturn(Mockito.mock(Block.class)); // 非 ChestBlock ⇒ 单 BE 分支
        Mockito.when(level.getBlockState(POS)).thenReturn(state);
        Mockito.when(level.getBlockEntity(POS)).thenReturn(be);
        return level;
    }

    private static BlockEntity anyBe() {
        return Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
    }

    @Test
    @DisplayName("缓存键带维度前缀 → 只认该维度（前缀键此前解析失败 ⇒ 全世界容器 tooltip 停更）")
    void prefixedKey_targetsItsDimension() {
        List<ServerLevel> levels = List.of(
            serverLevel(Level.OVERWORLD, anyBe()),
            serverLevel(Level.NETHER, null));       // 下界该位置没有 BE

        var result = LivingItem.runtimeContainerPositions(levels, "minecraft:overworld|chest_1_64_2");

        assertNotNull(result);
        assertEquals(List.of(POS), List.copyOf(result), "应按键里的维度命中主世界那个容器");
    }

    @Test
    @DisplayName("前缀声明的主世界该位置无 BE → 空集合（不越维度找补，宁可不发也不错发）")
    void prefixedKey_noBeInDeclaredDimension_doesNotFallThrough() {
        List<ServerLevel> levels = List.of(
            serverLevel(Level.OVERWORLD, null),          // 主世界该位置没有方块实体
            serverLevel(Level.NETHER, anyBe()));

        var result = LivingItem.runtimeContainerPositions(levels, "minecraft:overworld|chest_1_64_2");

        assertNotNull(result);
        assertTrue(result.isEmpty(), "键声明主世界 ⇒ 主世界没 BE 就是没容器，不许拿下界的顶包");
    }

    @Test
    @DisplayName("前缀声明的维度不在候选列表 → 空集合")
    void prefixedKey_unknownDimension_returnsEmpty() {
        List<ServerLevel> levels = List.of(serverLevel(Level.NETHER, anyBe()));

        assertTrue(LivingItem.runtimeContainerPositions(levels, "minecraft:overworld|chest_1_64_2").isEmpty());
    }

    @Test
    @DisplayName("裸键（无维度前缀，退化形态/测试替身）→ 扫描全部世界的首个命中（旧行为保持）")
    void rawKey_scansAllLevels() {
        List<ServerLevel> levels = List.of(
            serverLevel(Level.OVERWORLD, null),          // 主世界没有 ⇒ 继续查下一个世界
            serverLevel(Level.NETHER, anyBe()));

        var result = LivingItem.runtimeContainerPositions(levels, "chest_1_64_2");

        assertEquals(List.of(POS), List.copyOf(result));
    }

    @Test
    @DisplayName("背包键 / 坏键 → 空集合（背包分支在 LivingItemRuntimeSync 另走，解析不出坐标即安全跳过）")
    void playerAndMalformedKeys_returnEmpty() {
        List<ServerLevel> levels = List.of(serverLevel(Level.OVERWORLD, anyBe()));

        assertTrue(LivingItem.runtimeContainerPositions(levels, "player_550e8400-e29b-41d4-a716-446655440000").isEmpty());
        assertTrue(LivingItem.runtimeContainerPositions(levels, "minecraft:overworld|not_a_chest_key").isEmpty());
        assertTrue(LivingItem.runtimeContainerPositions(levels, null).isEmpty());
    }

    @Test
    @DisplayName("实体容器键 → 逐维 getEntity(uuid) 反查实体的【当前】位置（2026-10-21）")
    void entityKey_resolvesToLivePosition() {
        UUID id = UUID.fromString("0f1c3b8e-7a2d-4e5f-9c6b-1d2e3f4a5b6c");
        Entity boat = Mockito.mock(Entity.class);
        Mockito.when(boat.blockPosition()).thenReturn(new BlockPos(11, 64, -7));

        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.getEntity(id)).thenReturn(boat);

        var result = LivingItem.runtimeContainerPositions(List.of(level), "entity_" + id);

        assertEquals(List.of(new BlockPos(11, 64, -7)), List.copyOf(result),
            "实体容器的 viewer 匹配必须拿【实时】位置 —— GUI 开着时船可能正在移动");
    }

    @Test
    @DisplayName("实体容器键：实体不存在（已卸载/移除）→ 空集合 = 不发包")
    void entityKey_unknownEntity_returnsEmpty() {
        UUID id = UUID.fromString("0f1c3b8e-7a2d-4e5f-9c6b-1d2e3f4a5b6c");
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.getEntity(id)).thenReturn(null);

        assertTrue(LivingItem.runtimeContainerPositions(List.of(level), "entity_" + id).isEmpty());
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
