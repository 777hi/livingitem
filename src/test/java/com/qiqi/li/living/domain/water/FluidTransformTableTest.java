package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

/**
 * 流体转化表 JSON 加载语义守卫（F4，2026-10-03）——
 * 三层来源 / 玩家覆盖与删除 / 坏值跳过 / 未知字段不崩 / 转化口径（等量替换 + 缩容等待 + 非活过滤）。
 *
 * <p>口径与 {@code InteractionRuleConfigTest} 一致（同构加载器）。</p>
 */
class FluidTransformTableTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        FluidTransformTable.init(tempDir);
    }

    private void writeUserFile(String json) throws Exception {
        Files.writeString(tempDir.resolve("fluid_transforms.json"), json);
    }

    // ── 1. 内置数据 ──────────────────────────────────────────

    @Test
    @DisplayName("内置 JSON 全部装载成功：水桶/岩浆桶转化 + 16 色混凝土，物品/流体真实存在")
    void bundledTransformsLoad() {
        FluidTransformTable.load();

        int count = FluidTransformTable.entryCount();
        assertTrue(count >= 18, "内置转化应至少 18 条（1 水桶 + 16 混凝土 + 1 岩浆桶），实际 " + count);
        assertEquals(count, FluidTransformTable.bundledCount(),
            "无玩家文件时全部条目都应是内置的");

        // 抽查：水 + 空桶 → 水桶；岩浆 + 空桶 → 岩浆桶；白/黑混凝土粉末 → 混凝土
        var water = Fluids.WATER.getFluidType();
        assertEquals(Items.LAVA_BUCKET,
            FluidTransformTable.transform(Fluids.LAVA.getFluidType(), new ItemStack(Items.BUCKET)).getItem(),
            "活熔岩接入：空桶在岩浆源格 → 岩浆桶（消耗源）");
        assertEquals(Items.WATER_BUCKET,
            FluidTransformTable.transform(water, new ItemStack(Items.BUCKET)).getItem());
        assertEquals(Items.WHITE_CONCRETE,
            FluidTransformTable.transform(water, new ItemStack(Items.WHITE_CONCRETE_POWDER)).getItem());
        assertEquals(Items.BLACK_CONCRETE,
            FluidTransformTable.transform(water, new ItemStack(Items.BLACK_CONCRETE_POWDER)).getItem());
    }

    // ── 2. 玩家差异 ──────────────────────────────────────────

    @Test
    @DisplayName("玩家文件：追加新条目 / 按 id 覆盖内置 / removed 删除")
    void userDelta_appendOverrideRemove() throws Exception {
        writeUserFile("""
            {
              "version": 1,
              "removed": ["water_black_concrete"],
              "transforms": [
                { "id": "water_empty_bucket", "fluid": "minecraft:water",
                  "input": "minecraft:bucket", "output": "minecraft:lava_bucket" },
                { "id": "water_dirt_to_grass", "fluid": "minecraft:water",
                  "input": "minecraft:dirt", "output": "minecraft:grass_block" }
              ]
            }""");
        FluidTransformTable.load();

        var water = Fluids.WATER.getFluidType();
        assertEquals(Items.LAVA_BUCKET,
            FluidTransformTable.transform(water, new ItemStack(Items.BUCKET)).getItem(),
            "按 id 覆盖内置（空桶→岩浆桶）");
        assertEquals(Items.GRASS_BLOCK,
            FluidTransformTable.transform(water, new ItemStack(Items.DIRT)).getItem(),
            "玩家追加条目生效");
        assertNull(FluidTransformTable.transform(water, new ItemStack(Items.BLACK_CONCRETE_POWDER)),
            "removed 删除的内置条目不复活");
        assertEquals(18, FluidTransformTable.entryCount(),
            "17 内置生效（black_concrete 被 removed，含岩浆桶）+ 1 玩家追加");
        assertEquals(18, FluidTransformTable.bundledCount(),
            "bundled 集合含全部内置锚点（removed/覆盖后仍是锚点，同 InteractionRuleConfig 口径）");
    }

    @Test
    @DisplayName("玩家文件损坏：version 不识别 ⇒ 跳过整个文件（内置不受影响）")
    void brokenUserFile_skipped() throws Exception {
        writeUserFile("""
            {
              "version": 999,
              "transforms": [
                { "id": "x", "fluid": "minecraft:water", "input": "minecraft:dirt",
                  "output": "minecraft:grass_block" }
              ]
            }""");
        FluidTransformTable.load();

        assertEquals(FluidTransformTable.entryCount(), FluidTransformTable.bundledCount(),
            "坏文件整份跳过 ⇒ 只有内置条目");
        assertNull(FluidTransformTable.transform(Fluids.WATER.getFluidType(),
            new ItemStack(Items.DIRT)), "坏文件里的条目不生效");
    }

    // ── 3. 沉默即缺陷：坏条目跳过 ────────────────────────────

    @Test
    @DisplayName("坏条目跳过：未知字段 / 未知物品 / 缺字段，其余条目照常生效")
    void badEntries_skippedOthersSurvive() throws Exception {
        writeUserFile("""
            {
              "version": 1,
              "transforms": [
                { "id": "typo_field", "fluid": "minecraft:water", "inpt": "minecraft:dirt",
                  "output": "minecraft:grass_block" },
                { "id": "unknown_item", "fluid": "minecraft:water", "input": "minecraft:not_a_real_item",
                  "output": "minecraft:grass_block" },
                { "id": "unknown_fluid", "fluid": "minecraft:not_a_real_fluid",
                  "input": "minecraft:dirt", "output": "minecraft:grass_block" },
                { "id": "missing_field", "fluid": "minecraft:water", "input": "minecraft:dirt" },
                { "id": "water_good", "fluid": "minecraft:water", "input": "minecraft:dirt",
                  "output": "minecraft:grass_block" }
              ]
            }""");
        FluidTransformTable.load();

        assertEquals(1, FluidTransformTable.entryCount() - FluidTransformTable.bundledCount(),
            "5 条里只有 1 条好的生效");
        assertEquals(Items.GRASS_BLOCK,
            FluidTransformTable.transform(Fluids.WATER.getFluidType(),
                new ItemStack(Items.DIRT)).getItem());
    }

    // ── 4. 转化口径 ──────────────────────────────────────────

    @Test
    @DisplayName("缩容等待：空桶堆叠 >1 不转化（水桶最大堆叠 1）；=1 立即转化")
    void transform_shrinkingStackStalls() {
        FluidTransformTable.load();
        var water = Fluids.WATER.getFluidType();

        assertNull(FluidTransformTable.transform(water, new ItemStack(Items.BUCKET, 16)),
            "16 桶 ⇒ 整槽无法等量替换 ⇒ 等待");
        var one = FluidTransformTable.transform(water, new ItemStack(Items.BUCKET));
        assertEquals(Items.WATER_BUCKET, one.getItem());
        assertEquals(1, one.getCount(), "等量替换");
    }

    @Test
    @DisplayName("活物品不转化；非转化表的物品返回 null；等比转化不受缩容限制（混凝土 64→64）")
    void transform_livingFilteredAndRatioKept() {
        FluidTransformTable.load();
        var water = Fluids.WATER.getFluidType();

        ItemStack livingBucket = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(livingBucket, true);
        assertNull(FluidTransformTable.transform(water, livingBucket), "活物品不转化（挤没在先，防御在后）");

        assertNull(FluidTransformTable.transform(water, new ItemStack(Items.DIRT)), "表外物品不转化");
        assertEquals(Items.LAVA_BUCKET,
            FluidTransformTable.transform(Fluids.LAVA.getFluidType(), new ItemStack(Items.BUCKET)).getItem(),
            "岩浆已接入：空桶 → 岩浆桶");
        assertNull(FluidTransformTable.transform(Fluids.LAVA.getFluidType(), new ItemStack(Items.DIRT)),
            "表外物品（岩浆 + 泥土）不转化");

        var big = FluidTransformTable.transform(water, new ItemStack(Items.WHITE_CONCRETE_POWDER, 64));
        assertEquals(64, big.getCount(), "等比转化（64 ≤ 64 堆叠上限）整槽通过");
    }
}
