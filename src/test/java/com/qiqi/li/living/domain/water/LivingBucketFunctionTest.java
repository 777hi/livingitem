package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活桶（2026-10-04 逻辑纠偏定稿）—— <b>桶只是载体，零私有状态</b>。
 *
 * <p>口径（用户拍板）：「活桶」= 任意 {@code BucketItem} × 活标记；内容状态就是原版
 * {@code BucketItem.content}（活化水桶天然装水）；形态变换走 {@code Fluid.getBucket()}
 * 注册映射（汲水后「变成活水桶」、倒水后「变成活空桶」），活标记全程保留。</p>
 */
class LivingBucketFunctionTest {

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    // ── 判定 ─────────────────────────────────────────────────

    @Test
    @DisplayName("判定：活水桶/活岩浆桶/活空桶都是活桶（BucketItem 家族通吃）")
    void isLivingBucket_bucketFamily() {
        assertTrue(LivingBucketFunction.isLivingBucket(living(new ItemStack(Items.WATER_BUCKET))),
            "活水桶（旧存档/活化直取）天然被认领 —— content 就是水");
        assertTrue(LivingBucketFunction.isLivingBucket(living(new ItemStack(Items.LAVA_BUCKET))));
        assertTrue(LivingBucketFunction.isLivingBucket(living(new ItemStack(Items.BUCKET))));
        assertFalse(LivingBucketFunction.isLivingBucket(living(new ItemStack(Items.DIAMOND))),
            "非桶物品不是活桶");
        assertFalse(LivingBucketFunction.isLivingBucket(new ItemStack(Items.WATER_BUCKET)),
            "无活标记 = 不是活桶");
    }

    @Test
    @DisplayName("内容状态 = 原版 BucketItem.content：活化水桶天然满、空桶天然空")
    void content_comesFromVanillaBucketItem() {
        assertTrue(LivingBucketFunction.hasFullBucket(living(new ItemStack(Items.WATER_BUCKET))),
            "水桶 content=water —— 无需任何私有组件/推导");
        assertTrue(LivingBucketFunction.isEmptyBucket(living(new ItemStack(Items.BUCKET))));
        assertEquals(Fluids.LAVA, LivingBucketFunction.getBucketFluid(living(new ItemStack(Items.LAVA_BUCKET))));
    }

    // ── 形态变换（右键一下切换物品）────────────────────────────

    @Test
    @DisplayName("倒水：活水桶 → 活空桶（排空 = BUCKET），活标记保留")
    void withFluid_drain_swapsToEmptyBucket() {
        var held = living(new ItemStack(Items.WATER_BUCKET));

        var out = LivingBucketFunction.withFluid(held, Fluids.EMPTY);

        assertEquals(Items.BUCKET, out.getItem(), "倒水后「变成活空桶」");
        assertTrue(LivingItemManager.isLivingItem(out), "活标记保留");
        assertTrue(LivingBucketFunction.isEmptyBucket(out));
    }

    @Test
    @DisplayName("汲水：活空桶 + 水 → 活水桶；+ 岩浆 → 活岩浆桶（Fluid.getBucket 注册映射）")
    void withFluid_fill_swapsToFluidBucket() {
        var empty = living(new ItemStack(Items.BUCKET));

        var water = LivingBucketFunction.withFluid(empty, Fluids.WATER);
        assertEquals(Items.WATER_BUCKET, water.getItem(), "汲水后「变成活水桶」");
        assertTrue(LivingItemManager.isLivingItem(water));

        var lava = LivingBucketFunction.withFluid(empty, Fluids.LAVA);
        assertEquals(Items.LAVA_BUCKET, lava.getItem(), "汲岩浆 → 活岩浆桶");
        assertTrue(LivingItemManager.isLivingItem(lava));
    }

    @Test
    @DisplayName("换宿主后最大堆叠随形态：倒空的活空桶可堆 16")
    void withFluid_drain_maxStackFollowsHost() {
        var held = living(new ItemStack(Items.WATER_BUCKET));
        var out = LivingBucketFunction.withFluid(held, Fluids.EMPTY);
        assertEquals(16, out.getMaxStackSize(), "空桶形态应恢复 16 堆叠");
        assertTrue(out.is(Items.BUCKET));
    }

    @Test
    @DisplayName("形态不变时返回原实例（零新对象）")
    void withFluid_sameShape_returnsSameInstance() {
        var held = living(new ItemStack(Items.WATER_BUCKET));
        assertSame(held, LivingBucketFunction.withFluid(held, Fluids.WATER),
            "已是水桶形态再灌水 ⇒ 无需变换");
    }
}
