package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.tnt.LivingTntFunction;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活化门面守卫（D1 第一步，2026-09-27）。
 *
 * <p>锁住的契约：<b>不允许活化「没有任何功能认领」的物品</b>。
 * 活化一个无人认领的物品只有副作用（活物品隔离：不传输 / 不熔炼 / 不作燃料）而零收益，
 * 这正是 P1-3 的本体。
 *
 * <p>同时锁住 {@code hasAnyFunctionFor} 的 copy 探针实现 ——
 * 直接调 {@code getApplicableFunctions} 恒为 false（它对未活化的物品直接返回空，
 * 而各 {@code canApply} 内部也都依赖 {@code isLivingItem}），见该方法 javadoc。
 */
@DisplayName("活化门面 · 拒绝无功能认领的活化（D1 ①）")
class LivingItemActivationTest {

    @BeforeAll
    static void registerFunctions() {
        LivingItemManager.registerFunction(new LivingTntFunction());
    }

    @Test
    @DisplayName("① 有对应功能的物品 —— 允许活化")
    void claimableItemIsAllowed() {
        ItemStack tnt = new ItemStack(Items.TNT);
        assertTrue(LivingItemManager.hasAnyFunctionFor(tnt), "TNT 有 LivingTntFunction 认领");
        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(tnt, true, LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("② 无任何功能认领的物品 —— 拒绝活化（否则只有隔离副作用、零收益）")
    void unclaimedItemIsDenied() {
        ItemStack stick = new ItemStack(Items.STICK);
        assertFalse(LivingItemManager.hasAnyFunctionFor(stick), "木棍没有任何功能认领");
        assertEquals(LivingItemActivation.Result.DENY_UNCLAIMED,
            LivingItemActivation.evaluate(stick, true, LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("③ 取消活化总是允许 —— 恢复成普通物品没有副作用")
    void deactivationAlwaysAllowed() {
        ItemStack stick = new ItemStack(Items.STICK);
        LivingItemManager.setLiving(stick, true);
        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(stick, false, LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("④ 探针不改变原物品 —— 预判不得给物品打上 IS_LIVING")
    void probeDoesNotMutateOriginal() {
        ItemStack tnt = new ItemStack(Items.TNT);
        LivingItemManager.hasAnyFunctionFor(tnt);
        assertFalse(LivingItemManager.isLivingItem(tnt), "预判用了 copy，原物品必须保持未活化");
    }
}
