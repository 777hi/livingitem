package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 作物分类器回归守卫 —— 第八轮实测踩坑（2026-09-14）双根因：
 *
 * <p>① 火把花「maxAge 超属性值域」怪癖：AGE 属性是 AGE_1（值域 0~1）而
 * getMaxAge()=2，成熟态 getStateForAge(2) 直接变成 TORCHFLOWER 花方块——
 * 作物方块本身没有 age=2 状态。stateForAge(,2) 返回 null 曾导致成熟渲染空白
 * + matureStateFor 冻结失败零产出。</p>
 *
 * <p>② 瓶子草 PitcherCropBlock extends DoublePlantBlock 而非 CropBlock，
 * 白名单四 instanceof 全不中 + pitcher_pod 不在 c:seeds 标签 → 三层准入全漏
 * 无法种植。</p>
 *
 * <p>纯逻辑测试（不滚战利品表——那需要 FML Level；产出侧由游戏实测覆盖）。</p>
 */
class CropClassifierTest {

    // ── 火把花：maxAge / 越界 / 显示回退 ──

    @Test
    @DisplayName("火把花 maxAge=2（CropBlock.getMaxAge 分支），AGE 属性值域只有 0~1")
    void torchflower_maxAgeBeyondAgePropertyRange() {
        assertEquals(2, CropClassifier.getMaxAge(Blocks.TORCHFLOWER_CROP));
        assertNull(CropClassifier.stateForAge(Blocks.TORCHFLOWER_CROP, 2),
            "作物方块没有 age=2 状态——越界必须返回 null（不凭空构态）");
    }

    @Test
    @DisplayName("火把花成熟渲染回退：displayStateFor(,2) = 花方块默认态")
    void torchflower_matureDisplayFallsBackToFlower() {
        BlockState state = CropClassifier.displayStateFor(Blocks.TORCHFLOWER_CROP, 2);
        assertNotNull(state, "成熟越界 + 已注册收获形态 → 必须回退到花方块");
        assertEquals(Blocks.TORCHFLOWER.defaultBlockState(), state);
    }

    @Test
    @DisplayName("火把花未成熟阶段照常：displayStateFor(,0/1) = 作物态；超熟越界也回退花")
    void torchflower_immatureStagesRenderNormally() {
        assertEquals(CropClassifier.stateForAge(Blocks.TORCHFLOWER_CROP, 0),
            CropClassifier.displayStateFor(Blocks.TORCHFLOWER_CROP, 0));
        assertEquals(CropClassifier.stateForAge(Blocks.TORCHFLOWER_CROP, 1),
            CropClassifier.displayStateFor(Blocks.TORCHFLOWER_CROP, 1));
        // age=3 ≥ maxAge=2 同样是「已成熟」——一律回退花方块（钳制语义与生长一致）
        assertEquals(Blocks.TORCHFLOWER.defaultBlockState(),
            CropClassifier.displayStateFor(Blocks.TORCHFLOWER_CROP, 3));
    }

    @Test
    @DisplayName("普通作物 displayStateFor 与 stateForAge 全程一致（无回归）")
    void normalCrops_displayStateForPassthrough() {
        for (int age = 0; age <= 7; age++) {
            assertEquals(CropClassifier.stateForAge(Blocks.WHEAT, age),
                CropClassifier.displayStateFor(Blocks.WHEAT, age));
        }
    }

    // ── 瓶子草：种植准入 ──

    @Test
    @DisplayName("瓶子草荚（pitcher_pod）三层准入：Block 白名单命中（DoublePlantBlock 系作物）")
    void pitcherPod_admittedViaBlockWhitelist() {
        assertTrue(CropClassifier.isSeedPlantableOnFarmland(new ItemStack(Items.PITCHER_POD)),
            "PitcherCropBlock extends DoublePlantBlock——白名单显式列入后必须可种植");
        assertTrue(CropClassifier.isKnownCropBlock(Blocks.PITCHER_CROP));
    }

    @Test
    @DisplayName("瓶子草 maxAge=4（age 属性真实上限兜底）+ 下部件成熟态 HALF 默认 LOWER")
    void pitcherCrop_maxAgeAndMatureState() {
        assertEquals(4, CropClassifier.getMaxAge(Blocks.PITCHER_CROP));
        BlockState mature = CropClassifier.stateForAge(Blocks.PITCHER_CROP, 4);
        assertNotNull(mature);
        assertEquals(net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER,
            mature.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF),
            "HALF 默认 LOWER——战利品表全部产出池的 half=lower 条件恰好命中");
    }

    // ── 收获形态注册表：原版条目可解析 ──

    @Test
    @DisplayName("HARVEST_BLOCKS 原版条目可解析：火把花 → 花方块")
    void harvestBlocks_vanillaEntriesResolve() {
        assertEquals(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(Blocks.TORCHFLOWER),
            CropClassifier.getHarvestBlockId(Blocks.TORCHFLOWER_CROP));
    }

    // ── 柱状多段作物（同方块属性分段）──

    @Test
    @DisplayName("未注册柱状段的作物（小麦）getColumnParts 返回空表")
    void columnParts_unregisteredCropReturnsEmpty() {
        assertTrue(CropClassifier.getColumnParts(Blocks.WHEAT, 7).isEmpty(),
            "无柱状注册 → 空表（调用方渲染循环零迭代）");
    }

    @Test
    @DisplayName("柱状段属性覆盖正确应用：DOUBLE_BLOCK_HALF 翻转 + 同 age")
    void columnParts_propertyOverridesApplied() {
        // 借原版瓶子草验证机制（HALF 属性翻转与 KC 水稻的 location 同为属性覆盖）：
        // 未注册的方块注册临时条目，断言覆盖生效后必须清理
        net.minecraft.resources.ResourceLocation id =
            net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath("minecraft", "pitcher_crop");
        CropClassifier.registerColumnParts(id, java.util.List.of(java.util.Map.of("half", "upper")));
        try {
            java.util.List<net.minecraft.world.level.block.state.BlockState> parts =
                CropClassifier.getColumnParts(Blocks.PITCHER_CROP, 4);
            assertEquals(1, parts.size());
            assertEquals(net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER,
                parts.get(0).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF));
            assertEquals(4, parts.get(0).getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_4),
                "age 与基础态保持同步");
        } finally {
            // 静态注册表跨测试累积——临时条目必须清理
            CropClassifierTest.clearColumnParts(id);
        }
    }

    @Test
    @DisplayName("属性不存在的段静默跳过不抛异常；基础态无 age 时整表为空")
    void columnParts_missingPropertySkippedSilently() {
        net.minecraft.resources.ResourceLocation id =
            net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath("minecraft", "pitcher_crop");
        // 属性名不存在：整段跳过
        CropClassifier.registerColumnParts(id, java.util.List.of(
            java.util.Map.of("no_such_property", "1")));
        try {
            assertTrue(CropClassifier.getColumnParts(Blocks.PITCHER_CROP, 4).isEmpty(),
                "属性缺失的段静默跳过 → 空表");
        } finally {
            CropClassifierTest.clearColumnParts(id);
        }
    }

    /** 测试隔离：从 COLUMN_PARTS 移除临时注册（静态注册表跨测试累积） */
    static void clearColumnParts(net.minecraft.resources.ResourceLocation id) {
        // 通过注册一个空覆盖列表达到移除效果——getColumnParts 对空列表返回空表
        CropClassifier.registerColumnParts(id, java.util.List.of());
    }
}
