package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活桶世界取水（priming，2026-10-04）—— 原版 {@code BucketPickup} 路径 + 活标记保留。
 *
 * <p>取水 = 原版 {@code pickupBlock}（含「拿走世界水源」语义），拾取结果经
 * {@code asLivingBucketForm} 转为活桶形态；数量/创造模式走原版
 * {@code ItemUtils.createFilledResult}。事件/视线投射/声音属集成层，游戏内验证。</p>
 */
class LivingBucketWorldUseTest {

    @Test
    @DisplayName("拾取结果活物品化：原版水桶 → 活水桶形态，活标记保留")
    void asLivingBucketForm_preservesLivingMarker() {
        ItemStack held = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(held, true);

        ItemStack picked = new ItemStack(Items.WATER_BUCKET);   // 原版 pickupBlock 的返回
        ItemStack out = LivingBucketWorldUse.asLivingBucketForm(picked, held);

        assertEquals(Items.WATER_BUCKET, out.getItem(), "取水后「变成活水桶」");
        assertTrue(LivingItemManager.isLivingItem(out), "活标记保留（原版 swap 会丢）");
    }

    @Test
    @DisplayName("拾取岩浆：原版岩浆桶 → 活岩浆桶形态")
    void asLivingBucketForm_lava() {
        ItemStack held = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(held, true);

        ItemStack picked = new ItemStack(Items.LAVA_BUCKET);
        ItemStack out = LivingBucketWorldUse.asLivingBucketForm(picked, held);

        assertEquals(Items.LAVA_BUCKET, out.getItem());
        assertTrue(LivingItemManager.isLivingItem(out));
    }
}
