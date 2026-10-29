package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * R-04 回归守卫：未开箱的战利品容器「不处理但保留区块」。
 *
 * <p>旧实现直接 {@code continue}，没置 {@code hasContainer} ⇒ 区块被移出处理缓存；
 * 而开箱（{@code lootTable} 变 null）不触发重扫 ⇒ 之后放入的活物品永不 tick。</p>
 */
class LivingItemChunkContainerScanTest {

    private static final ResourceKey<LootTable> LOOT = ResourceKey.create(
        Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/shipwreck_map"));

    @Test
    @DisplayName("R-04：未开箱的战利品容器不处理、不查能力，但区块必须保留（返回 0 而非 -1）")
    void unopenedLootChest_retainsChunkWithoutCapabilityQuery() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        LevelChunk chunk = Mockito.mock(LevelChunk.class);
        BlockPos pos = new BlockPos(1, 64, 2);
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLootTable(LOOT, 0L);
        Mockito.when(chunk.getBlockEntities()).thenReturn(Map.of(pos, chest));

        int result = LivingItem.processChunkContainers(
            level, chunk, new IdentityHashMap<IItemHandler, Boolean>(), new HashSet<>());

        assertEquals(0, result,
            "0 = 没有可处理的容器，但区块内确有容器 ⇒ 不能返回 -1（否则区块被移出缓存）");
        Mockito.verifyNoInteractions(level);
    }

    @Test
    @DisplayName("开箱后 lootTable 已置空：回到普通能力查询路径")
    void openedLootChest_fallsThroughToCapability() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        LevelChunk chunk = Mockito.mock(LevelChunk.class);
        BlockPos pos = new BlockPos(1, 64, 2);
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLootTable(null); // 原版 unpackLootTable 末行
        Mockito.when(chunk.getBlockEntities()).thenReturn(Map.of(pos, chest));

        int result = LivingItem.processChunkContainers(
            level, chunk, new IdentityHashMap<IItemHandler, Boolean>(), new HashSet<>());

        assertEquals(-1, result, "没有 ItemHandler ⇒ 不是容器区块，应移出缓存");
        Mockito.verify(level).getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }
}
