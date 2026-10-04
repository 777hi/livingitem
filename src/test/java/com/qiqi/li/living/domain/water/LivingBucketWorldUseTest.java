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
 * 活桶世界取水（priming，2026-10-04）—— 灌入语义与**换宿主**（空桶形态 → 水桶/岩浆桶形态）。
 *
 * <p>背景：桶源退役后取水闭环断链（满活桶唯一来源是汲水，汲水需要已有源），
 * {@code LivingBucketWorldUse} 补「空活桶对世界流体源取水」。本类钉住其纯逻辑部分：
 * 灌入的是<b>命中流体</b>的一整桶、不换物品、活标记保留。视线投射/事件取消属集成层，
 * 游戏内验证。</p>
 */
class LivingBucketWorldUseTest {

    @Test
    @DisplayName("世界取水：灌满后换宿主为水桶形态，活标记保留（换宿主模型）")
    void fillFromSource_swapsHostToWaterBucket() {
        ItemStack held = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(held, true);

        FluidState state = mock(FluidState.class);
        when(state.getType()).thenReturn(Fluids.WATER);

        ItemStack filled = LivingBucketWorldUse.fillFromSource(held, state);

        assertEquals(Items.WATER_BUCKET, filled.getItem(), "灌满后应呈现水桶形态（原版桶心智）");
        var content = LivingBucketFunction.getContent(filled);
        assertEquals(FluidType.BUCKET_VOLUME, content.getAmount(), "一次一整桶");
        assertEquals(Fluids.WATER, content.getFluid(), "灌入的是命中的流体");
        assertTrue(LivingItemManager.isLivingItem(filled), "换宿主后活标记保留");
    }

    @Test
    @DisplayName("重复取水覆盖而非叠加：取岩浆换岩浆桶形态（桶容量语义）")
    void fillFromSource_overwritesNotAccumulates() {
        ItemStack held = new ItemStack(Items.BUCKET);
        LivingItemManager.setLiving(held, true);

        FluidState lava = mock(FluidState.class);
        when(lava.getType()).thenReturn(Fluids.LAVA);

        ItemStack afterWater = LivingBucketWorldUse.fillFromSource(held, mockWater());
        ItemStack afterLava = LivingBucketWorldUse.fillFromSource(afterWater, lava);

        assertEquals(Items.LAVA_BUCKET, afterLava.getItem(), "取岩浆后换岩浆桶形态");
        var content = LivingBucketFunction.getContent(afterLava);
        assertEquals(Fluids.LAVA, content.getFluid(), "后一次取水覆盖（岩浆替换水）");
        assertEquals(FluidType.BUCKET_VOLUME, content.getAmount(), "仍是恰好一桶，不叠加");
    }

    private static FluidState mockWater() {
        FluidState state = mock(FluidState.class);
        when(state.getType()).thenReturn(Fluids.WATER);
        return state;
    }
}
