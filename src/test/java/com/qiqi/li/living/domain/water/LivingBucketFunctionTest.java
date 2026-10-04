package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活桶换宿主模型（2026-10-04）—— 内容组件是权威，宿主物品跟随内容变换。
 *
 * <p>口径（用户拍板）：倒水后应「变成活空桶」、吸水后「变成活水桶」—— 空桶/水桶
 * <b>物品形态</b>之间的变换，活标记全程保留。另含<b>宿主隐含内容</b>：活化水桶/岩浆桶
 * （无组件）直接视为满桶 —— 兼容旧活水桶与「活化水桶直取」路径。</p>
 */
class LivingBucketFunctionTest {

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    // ── 宿主隐含内容 ─────────────────────────────────────────

    @Test
    @DisplayName("宿主隐含内容：活化的水桶（无组件）直接视为满水桶 —— 兼容旧活水桶")
    void impliedContent_livingWaterBucketIsFull() {
        var held = living(new ItemStack(Items.WATER_BUCKET));   // 活化不写内容组件

        assertTrue(LivingBucketFunction.hasFullBucket(held), "活化水桶 = 满桶（隐含内容）");
        assertEquals(Fluids.WATER, LivingBucketFunction.getContent(held).getFluid());
    }

    @Test
    @DisplayName("宿主隐含内容：活化岩浆桶 = 满岩浆；活化空桶 = 空")
    void impliedContent_lavaAndEmpty() {
        assertTrue(LivingBucketFunction.hasFullBucket(living(new ItemStack(Items.LAVA_BUCKET))),
            "活化岩浆桶 = 满岩浆");
        assertFalse(LivingBucketFunction.hasFullBucket(living(new ItemStack(Items.BUCKET))),
            "活化空桶 = 空");
    }

    // ── 换宿主变换 ───────────────────────────────────────────

    @Test
    @DisplayName("倒水（灌空）：水桶形态 → 空桶形态，活标记保留")
    void withContent_drain_swapsToEmptyBucket() {
        var held = living(new ItemStack(Items.WATER_BUCKET));

        var out = LivingBucketFunction.withContent(held, SimpleFluidContent.EMPTY);

        assertEquals(Items.BUCKET, out.getItem(), "倒空后应「变成活空桶」");
        assertTrue(LivingItemManager.isLivingItem(out), "活标记保留");
        assertTrue(LivingBucketFunction.isEmptyBucket(out));
    }

    @Test
    @DisplayName("汲水（灌满）：空桶形态 → 水桶形态；取岩浆 → 岩浆桶形态")
    void withContent_fill_swapsToFluidBucket() {
        var empty = living(new ItemStack(Items.BUCKET));

        var water = LivingBucketFunction.withContent(empty, SimpleFluidContent.copyOf(
            new net.neoforged.neoforge.fluids.FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME)));
        assertEquals(Items.WATER_BUCKET, water.getItem(), "汲满水应「变成活水桶」");
        assertTrue(LivingItemManager.isLivingItem(water));

        var lava = LivingBucketFunction.withContent(empty, SimpleFluidContent.copyOf(
            new net.neoforged.neoforge.fluids.FluidStack(Fluids.LAVA, FluidType.BUCKET_VOLUME)));
        assertEquals(Items.LAVA_BUCKET, lava.getItem(), "汲岩浆 → 岩浆桶形态");
    }

    @Test
    @DisplayName("换宿主不丢数量与组件：数量保留、宿主不变时零新对象")
    void withContent_preservesCountAndComponents() {
        var held = living(new ItemStack(Items.BUCKET, 3));

        var out = LivingBucketFunction.withContent(held, SimpleFluidContent.copyOf(
            new net.neoforged.neoforge.fluids.FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME)));

        // 3 个 > 水桶最大堆叠 1 ⇒ 保持空桶宿主（数量优先），内容组件照写
        assertEquals(Items.BUCKET, out.getItem(), "数量超宿主堆叠 ⇒ 保持原宿主");
        assertEquals(3, out.getCount());
        assertTrue(LivingItemManager.isLivingItem(out));
        assertTrue(LivingBucketFunction.hasFullBucket(out), "内容组件仍可读出满桶");
    }
}
