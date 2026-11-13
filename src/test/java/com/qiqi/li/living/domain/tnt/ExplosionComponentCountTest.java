package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活 TNT 计数/清空的<b>守恒</b>（2026-10-27 审查 P2-1，TODO 挂账 S1 的回归）。
 *
 * <p><b>被守的行为</b>：计数与清空曾在同一循环且用 int 求和 —— 总和回绕成负时
 * {@code totalTntCount <= 0} 早退，但物品已全部清空 ⇒ TNT 凭空消失、零爆炸、零日志。
 * 修复：求和改 long（槽数 × maxCount 实际不可回绕）+ 先统计后清空 + 异常留痕（I-C6）。</p>
 */
class ExplosionComponentCountTest {

    private static ItemStack livingTnt(int count) {
        ItemStack stack = new ItemStack(Items.TNT, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("两满槽 int 极值 ⇒ long 求和不回绕（原实现 int 求和得 -2 且物品已被清空）")
    void twoMaxStacks_sumDoesNotWrap() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.setItem(0, livingTnt(Integer.MAX_VALUE));
        ctx.setItem(1, livingTnt(Integer.MAX_VALUE));

        long total = ExplosionComponent.countLivingTnt(ctx);

        assertEquals(2L * Integer.MAX_VALUE, total,
            "int 求和会回绕成 -2 ⇒ 早退但物品已清空（丢物品 + 零爆炸 + 零日志）");
        assertEquals(Integer.MAX_VALUE, ctx.getItem(0).getCount(), "统计阶段不消耗物品");
    }

    @Test
    @DisplayName("清空只清活 TNT：普通 TNT 与非 TNT 物品保留")
    void clearRemovesOnlyLivingTnt() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.setItem(0, livingTnt(3));
        ctx.setItem(1, new ItemStack(Items.TNT, 8));       // 普通 TNT
        ctx.setItem(2, new ItemStack(Items.DIAMOND, 5));   // 非 TNT

        ExplosionComponent.clearLivingTnt(ctx);

        assertTrue(ctx.getItem(0).isEmpty(), "活 TNT 被清空");
        assertEquals(8, ctx.getItem(1).getCount(), "普通 TNT 不受影响");
        assertEquals(5, ctx.getItem(2).getCount(), "非 TNT 物品不受影响");
    }

    @Test
    @DisplayName("无效基础半径先拒绝，活 TNT 不消耗也不访问爆炸世界")
    void invalidRadius_preservesTnt() {
        for (float radius : new float[] { 0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE }) {
            FakeContainerContext ctx = new FakeContainerContext(27, 9).withBlockPos(new BlockPos(8, 64, 8));
            ctx.setItem(0, livingTnt(1));
            ServerLevel level = mock(ServerLevel.class);

            assertFalse(ExplosionComponent.ignite(ctx, level, radius, true), "radius=" + radius);
            assertEquals(1, ctx.getItem(0).getCount(), "拒绝参数不能吞 TNT，radius=" + radius);
            verifyNoInteractions(level);
        }
    }

    @Test
    @DisplayName("非服务端入口拒绝并保留活 TNT")
    void nonServerLevel_preservesTnt() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9).withBlockPos(new BlockPos(8, 64, 8));
        ctx.setItem(0, livingTnt(2));

        assertFalse(ExplosionComponent.ignite(ctx, mock(Level.class), 4, true));
        assertEquals(2, ctx.getItem(0).getCount());
    }

    @Test
    @DisplayName("无活 TNT ⇒ 计数 0（ignite 据此放弃且不清任何物品）")
    void noLivingTnt_countsZero() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.setItem(0, new ItemStack(Items.TNT, 8));

        assertEquals(0L, ExplosionComponent.countLivingTnt(ctx));
        assertEquals(8, ctx.getItem(0).getCount());
    }
}
