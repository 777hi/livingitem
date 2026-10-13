package com.qiqi.li.living.domain.brewing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 材料消耗（{@code consumeOne}）守卫 —— 2026-10-13 用户实测 bug 的回归。
 *
 * <p>背景：龙息（{@code Items.DRAGON_BREATH}）带合成残留物（玻璃瓶）<b>且可堆叠</b>（默认 64），
 * 而旧实现遇到「有残留物」就<b>整栈替换</b> ⇒ 「8 个龙息」变「1 个玻璃瓶」，7 个凭空消失。
 * 原版 {@code BrewingStandBlockEntity#doBrew} 的语义是 <b>先扣 1、再看残留物</b>：
 * 扣完为空 ⇒ 槽位变残留物；还有剩余 ⇒ 残留物掉落（活物品无坐标 ⇒ 改为另寻归宿）。</p>
 *
 * <p>本类钉住修复后的四条判据 + 一条「绝不整栈替换」的核心回归。</p>
 */
class BrewingMaterialConsumeTest {

    /** 材料槽（取非 0 的位置，避免与「扫描到的第一个空槽」重合而掩盖 bug） */
    private static final int MATERIAL = 4;

    private static FakeContainerContext container() {
        return new FakeContainerContext(27, 9);
    }

    @Test
    @DisplayName("普通材料（无残留物）：扣 1，槽位其余内容不变")
    void plainIngredientShrinksByOne() {
        FakeContainerContext ctx = container();
        ctx.set(MATERIAL, new ItemStack(Items.NETHER_WART, 3));

        LivingBrewingStandFunction.consumeOne(ctx, MATERIAL);

        assertEquals(Items.NETHER_WART, ctx.getItem(MATERIAL).getItem());
        assertEquals(2, ctx.getItem(MATERIAL).getCount(), "只扣 1 个");
    }

    @Test
    @DisplayName("⭐ 回归：龙息不整栈替换 —— 扣 1 保留剩余，玻璃瓶另放")
    void dragonBreathDoesNotReplaceWholeStack() {
        FakeContainerContext ctx = container();
        ctx.set(MATERIAL, new ItemStack(Items.DRAGON_BREATH, 8));

        LivingBrewingStandFunction.consumeOne(ctx, MATERIAL);

        assertEquals(Items.DRAGON_BREATH, ctx.getItem(MATERIAL).getItem(), "材料槽仍是龙息");
        assertEquals(7, ctx.getItem(MATERIAL).getCount(), "只扣 1 个（旧实现会整栈变 1 个玻璃瓶）");
        assertEquals(1, countOf(ctx, Items.GLASS_BOTTLE), "残留物玻璃瓶归位到容器空槽");
    }

    @Test
    @DisplayName("龙息 ×1：扣完为空 ⇒ 材料槽变成 1 个玻璃瓶（与原版同）")
    void dragonBreathSingleEmptiesIntoBottle() {
        FakeContainerContext ctx = container();
        ctx.set(MATERIAL, new ItemStack(Items.DRAGON_BREATH, 1));

        LivingBrewingStandFunction.consumeOne(ctx, MATERIAL);

        assertEquals(Items.GLASS_BOTTLE, ctx.getItem(MATERIAL).getItem(), "空栈 ⇒ 槽位变残留物");
        assertEquals(1, ctx.getItem(MATERIAL).getCount());
        assertEquals(0, countOf(ctx, Items.DRAGON_BREATH));
    }

    @Test
    @DisplayName("残留物并入已有的未满玻璃瓶堆（不新占槽位）")
    void dragonBreathMergesIntoPartialBottleStack() {
        FakeContainerContext ctx = container();
        ctx.set(MATERIAL, new ItemStack(Items.DRAGON_BREATH, 8));
        ctx.set(0, new ItemStack(Items.GLASS_BOTTLE, 5));
        int occupiedBefore = occupiedSlots(ctx);

        LivingBrewingStandFunction.consumeOne(ctx, MATERIAL);

        assertEquals(6, ctx.getItem(0).getCount(), "并入已有玻璃瓶堆");
        assertEquals(7, ctx.getItem(MATERIAL).getCount(), "材料只扣 1 个");
        assertEquals(occupiedBefore, occupiedSlots(ctx), "没有新占槽位");
    }

    @Test
    @DisplayName("容器满：扣 1 保留剩余，残留物丢弃（不崩、不整栈替换）")
    void dragonBreathContainerFullDiscardsRemainder() {
        FakeContainerContext ctx = container();
        for (int i = 0; i < ctx.getSize(); i++) {
            ctx.set(i, new ItemStack(Items.DIRT, 64));      // 全部占满
        }
        ctx.set(MATERIAL, new ItemStack(Items.DRAGON_BREATH, 8));

        LivingBrewingStandFunction.consumeOne(ctx, MATERIAL);

        assertEquals(7, ctx.getItem(MATERIAL).getCount(), "仍只扣 1 个");
        assertEquals(0, countOf(ctx, Items.GLASS_BOTTLE), "容器满 ⇒ 残留物无处安放（丢弃）");
    }

    @Test
    @DisplayName("空槽：无事发生")
    void emptySlotIsNoOp() {
        FakeContainerContext ctx = container();

        LivingBrewingStandFunction.consumeOne(ctx, MATERIAL);

        assertTrue(ctx.getItem(MATERIAL).isEmpty());
        assertEquals(0, occupiedSlots(ctx));
    }

    // ==================== 断言助手 ====================

    private static int countOf(FakeContainerContext ctx, Item item) {
        int n = 0;
        for (int i = 0; i < ctx.getSize(); i++) {
            ItemStack s = ctx.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static int occupiedSlots(FakeContainerContext ctx) {
        int n = 0;
        for (int i = 0; i < ctx.getSize(); i++) {
            if (!ctx.getItem(i).isEmpty()) n++;
        }
        return n;
    }
}
