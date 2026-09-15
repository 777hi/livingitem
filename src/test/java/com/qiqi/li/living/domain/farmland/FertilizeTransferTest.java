package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * tryFertilize 自动施肥回归守卫 —— 活漏斗把骨粉推向活耕地槽位的传输特殊分支
 * （2026-09-15 新增）。
 *
 * <p>语义与 GUI 活骨粉右键（BonemealHandler）共享 forceGrowthTick + equals
 * 零空转，但触发物口径有意区分：手动 = 活化能力（活骨粉），自动 = 物流集成
 * （普通骨粉即可）。守卫链与消耗（1 骨粉/次、无变化不消耗）在此逐项回归。</p>
 *
 * <p>纯逻辑测试：tryFertilize 只依赖 plant 组件读写 + forceGrowthTick（其
 * 未成熟分支不滚战利品表，无 ServerLevel 依赖；成熟冻结分支需要 level 的
 * random/loot——成熟用例用「已冻结」形态避开滚表，冻结触发路径由游戏实测覆盖）。
 * mock ServerLevel 仅满足签名（forceGrowthTick 未成熟分支不触碰它）。</p>
 */
class FertilizeTransferTest {

    private static final ServerLevel LEVEL = Mockito.mock(ServerLevel.class);

    /** 活耕地（带 IS_LIVING + FARMLAND_PLANT 组件） */
    private static ItemStack livingFarmland(int age, int maxAge) {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        LivingItemManager.setFarmlandPlant(stack,
            new FarmlandPlantComponent(Items.WHEAT_SEEDS, age, maxAge, 0L, -1, java.util.List.of()));
        return stack;
    }

    @Test
    @DisplayName("普通骨粉 + 未成熟活耕地 → age+1、骨粉 -1、返回 true")
    void fertilize_immatureFarmland_growsAndConsumes() {
        ItemStack farmland = livingFarmland(3, 7);
        ItemStack bonemeal = new ItemStack(Items.BONE_MEAL, 16);

        boolean ok = LivingFarmlandFunction.tryFertilize(farmland, bonemeal, LEVEL);

        assertTrue(ok, "未成熟 → 生长 tick 必定成功");
        assertEquals(4, LivingItemManager.getFarmlandPlant(farmland).age());
        assertEquals(15, bonemeal.getCount(), "施肥消耗 1 个骨粉");
    }

    @Test
    @DisplayName("已冻结成熟耕地（pendingDrops 非空）→ 全不变、false、骨粉零消耗")
    void fertilize_frozenMatureFarmland_noOpNoConsume() {
        ItemStack farmland = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(farmland, true);
        LivingItemManager.setFarmlandPlant(farmland, new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 7, 7, 0L, 0,
            java.util.List.of(new ItemStack(Items.WHEAT))));
        ItemStack bonemeal = new ItemStack(Items.BONE_MEAL, 16);

        boolean ok = LivingFarmlandFunction.tryFertilize(farmland, bonemeal, LEVEL);

        assertFalse(ok, "已冻结待输出 → forceGrowthTick 无变化 → 零空转不消耗");
        assertEquals(16, bonemeal.getCount(), "对着等待输出的耕地不烧骨粉");
        assertEquals(7, LivingItemManager.getFarmlandPlant(farmland).age());
    }

    @Test
    @DisplayName("骨粉 + 普通耕地（非活）→ false 不触发不消耗")
    void fertilize_nonLivingFarmland_rejected() {
        ItemStack plainFarmland = new ItemStack(Items.FARMLAND, 1);   // 无 IS_LIVING
        ItemStack bonemeal = new ItemStack(Items.BONE_MEAL, 16);

        assertFalse(LivingFarmlandFunction.tryFertilize(plainFarmland, bonemeal, LEVEL));
        assertEquals(16, bonemeal.getCount());
    }

    @Test
    @DisplayName("骨粉 + 未种植活耕地 → false 不消耗")
    void fertilize_unplantedFarmland_rejected() {
        ItemStack farmland = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(farmland, true);   // 无 FARMLAND_PLANT = 未种植
        ItemStack bonemeal = new ItemStack(Items.BONE_MEAL, 16);

        assertFalse(LivingFarmlandFunction.tryFertilize(farmland, bonemeal, LEVEL));
        assertEquals(16, bonemeal.getCount());
    }

    @Test
    @DisplayName("非骨粉货物（小麦）→ false 不触发")
    void fertilize_nonBonemealCargo_rejected() {
        ItemStack farmland = livingFarmland(3, 7);
        ItemStack wheat = new ItemStack(Items.WHEAT, 16);

        assertFalse(LivingFarmlandFunction.tryFertilize(farmland, wheat, LEVEL));
        assertEquals(3, LivingItemManager.getFarmlandPlant(farmland).age(), "非骨粉不触发生长");
        assertEquals(16, wheat.getCount());
    }

    @Test
    @DisplayName("tryFertilize 本身不区分活/普通骨粉（政策在漏斗侧：SlotInteractions 货物准入拒绝活骨粉）")
    void fertilize_livingBonemeal_alsoWorks() {
        ItemStack farmland = livingFarmland(3, 7);
        ItemStack livingBonemeal = new ItemStack(Items.BONE_MEAL, 16);
        LivingItemManager.setLiving(livingBonemeal, true);

        // 本方法只管施肥语义，不做货物准入——漏斗路径的「活物品不作货物」由
        // SlotInteractions.isEligibleCargo 统一执行（SlotInteractionCargoGateTest）
        assertTrue(LivingFarmlandFunction.tryFertilize(farmland, livingBonemeal, LEVEL));
        assertEquals(4, LivingItemManager.getFarmlandPlant(farmland).age());
        assertEquals(15, livingBonemeal.getCount());
    }
}
