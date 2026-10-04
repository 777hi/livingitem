package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活桶世界取水（priming，2026-10-04）—— 灌入语义与活标记保留。
 *
 * <p>背景：桶源退役后取水闭环断链（满活桶唯一来源是汲水，汲水需要已有源），
 * {@code LivingBucketWorldUse} 补「空活桶对世界流体源取水」。本类钉住其纯逻辑部分：
 * 灌入的是<b>命中流体</b>的一整桶、不换物品、活标记保留。视线投射/事件取消属集成层，
 * 游戏内验证。</p>
 */
class LivingBucketWorldUseTest {

    @Test
    @DisplayName("世界取水：灌入命中流体一整桶，不换物品、活标记保留")
    void fillFromSource_fillsOneBucketOfTheFluid() {
        ItemStack held = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(held, true);

        FluidState state = mock(FluidState.class);
        when(state.getType()).thenReturn(Fluids.WATER);

        LivingBucketWorldUse.fillFromSource(held, state);

        var content = LivingBucketFunction.getContent(held);
        assertFalse(content.isEmpty(), "取水后内容非空");
        assertEquals(FluidType.BUCKET_VOLUME, content.getAmount(), "一次一整桶");
        assertEquals(Fluids.WATER, content.getFluid(), "灌入的是命中的流体");
        assertEquals(Items.BUCKET, held.getItem(), "不换物品（原版 swap 会丢活标记）");
        assertTrue(LivingItemManager.isLivingItem(held), "活标记保留");
    }

    @Test
    @DisplayName("重复取水覆盖而非叠加：内容始终一桶（桶容量语义）")
    void fillFromSource_overwritesNotAccumulates() {
        ItemStack held = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(held, true);

        FluidState lava = mock(FluidState.class);
        when(lava.getType()).thenReturn(Fluids.LAVA);

        LivingBucketWorldUse.fillFromSource(held, mockWater());
        LivingBucketWorldUse.fillFromSource(held, lava);

        var content = LivingBucketFunction.getContent(held);
        assertEquals(Fluids.LAVA, content.getFluid(), "后一次取水覆盖（岩浆替换水）");
        assertEquals(FluidType.BUCKET_VOLUME, content.getAmount(), "仍是恰好一桶，不叠加");
    }

    private static FluidState mockWater() {
        FluidState state = mock(FluidState.class);
        when(state.getType()).thenReturn(Fluids.WATER);
        return state;
    }
}
