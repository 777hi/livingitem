package com.qiqi.li.living.domain.furnace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 燃料消耗的合成残留物语义测试（对齐原版 AbstractFurnaceBlockEntity#serverTick）。
 *
 * <p>回归背景：岩浆桶作为燃料曾被整桶吞掉，没有像原版一样留下空桶。</p>
 */
class LivingFurnaceFunctionTest {

    private static final int FUEL_SLOT = 4;

    @Test
    @DisplayName("岩浆桶点燃：燃料槽替换为空桶")
    void lavaBucket_leavesEmptyBucket() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FUEL_SLOT, new ItemStack(Items.LAVA_BUCKET));

        LivingFurnaceFunction.consumeFuel(ctx, FUEL_SLOT, ctx.getItem(FUEL_SLOT));

        ItemStack after = ctx.getItem(FUEL_SLOT);
        assertTrue(after.is(Items.BUCKET), "岩浆桶应留下空桶，实际为 " + after);
        assertEquals(1, after.getCount());
    }

    @Test
    @DisplayName("可堆叠的带残留燃料：整槽替换为 1 个残留物（对齐原版，不按数量折算）")
    void stackableRemainderFuel_replacedWholeSlot() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        // 原版对带残留物的燃料不 shrink 而是整槽替换；超堆叠数模拟 mod 可堆叠燃料
        ctx.set(FUEL_SLOT, new ItemStack(Items.LAVA_BUCKET, 2));

        LivingFurnaceFunction.consumeFuel(ctx, FUEL_SLOT, ctx.getItem(FUEL_SLOT));

        ItemStack after = ctx.getItem(FUEL_SLOT);
        assertTrue(after.is(Items.BUCKET));
        assertEquals(1, after.getCount());
    }

    @Test
    @DisplayName("普通燃料堆叠：只扣 1 个")
    void stackedFuel_shrinksOne() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FUEL_SLOT, new ItemStack(Items.COAL, 64));

        LivingFurnaceFunction.consumeFuel(ctx, FUEL_SLOT, ctx.getItem(FUEL_SLOT));

        ItemStack after = ctx.getItem(FUEL_SLOT);
        assertTrue(after.is(Items.COAL));
        assertEquals(63, after.getCount());
    }

    @Test
    @DisplayName("最后一个普通燃料：槽位清空")
    void lastFuel_clearsSlot() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FUEL_SLOT, new ItemStack(Items.COAL, 1));

        LivingFurnaceFunction.consumeFuel(ctx, FUEL_SLOT, ctx.getItem(FUEL_SLOT));

        assertTrue(ctx.getItem(FUEL_SLOT).isEmpty());
    }
}
