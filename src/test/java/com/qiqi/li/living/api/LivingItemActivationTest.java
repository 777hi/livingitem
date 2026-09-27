package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.tnt.LivingTntFunction;
import com.qiqi.li.living.api.ActivationRuleConfig.Action;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活化门面测试（D1）。
 *
 * <p>⭐ 锁住的核心口径（2026-09-27 用户定调）：<b>默认行为不可动</b> ——
 * 不配置任何规则时，任何物品都能被活化（模组原有行为）。
 * 「拒绝无功能认领」（{@code denyUnclaimed}）只是<b>显式开启</b>的可选项，默认必须关。
 *
 * <p>同时锁住 {@code hasAnyFunctionFor} 的 copy 探针实现 ——
 * 直接调 {@code getApplicableFunctions} 恒为 false（它对未活化的物品直接返回空，
 * 而各 {@code canApply} 内部也都依赖 {@code isLivingItem}），见该方法 javadoc。
 */
@DisplayName("活化门面 · 默认全放行，拦截只能显式开启（D1）")
class LivingItemActivationTest {

    @BeforeEach
    void resetRules() {
        // 规则配置是进程级静态状态，逐测试重置为「零配置」基准
        ActivationRuleConfig.loadFromString("{}");
    }

    @BeforeAll
    static void registerFunctions() {
        LivingItemManager.registerFunction(new LivingTntFunction());
    }

    @Test
    @DisplayName("① ⭐ 零配置时默认全放行 —— 原有行为不可动")
    void everythingAllowedByDefault() {
        assertTrue(LivingItemManager.hasAnyFunctionFor(new ItemStack(Items.TNT)));
        assertFalse(LivingItemManager.hasAnyFunctionFor(new ItemStack(Items.STICK)));

        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(new ItemStack(Items.STICK), true,
                LivingItemActivation.Via.PLAYER),
            "木棍没有任何功能认领，但默认也必须允许 —— 拦截只能显式开启");
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(new ItemStack(Items.STONE), true,
                LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("② 有对应功能的物品 —— 始终允许")
    void claimableItemIsAllowed() {
        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(new ItemStack(Items.TNT), true,
                LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("③ denyUnclaimed 显式开启后，无功能认领的活化才被拒绝")
    void unclaimedDeniedOnlyWhenEnabled() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"options":{"denyUnclaimed":true}}
            """);
        assertEquals(LivingItemActivation.Result.DENY_UNCLAIMED,
            LivingItemActivation.evaluate(new ItemStack(Items.STICK), true,
                LivingItemActivation.Via.PLAYER));
        // 有功能认领的照常放行
        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(new ItemStack(Items.TNT), true,
                LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("④ 取消活化总是允许 —— 恢复成普通物品没有副作用")
    void deactivationAlwaysAllowed() {
        ItemStack stick = new ItemStack(Items.STICK);
        LivingItemManager.setLiving(stick, true);
        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(stick, false, LivingItemActivation.Via.PLAYER));

        // 即使 denyUnclaimed 开启也一样（它只管 activate）
        ActivationRuleConfig.loadFromString(
            "{\"version\":1,\"options\":{\"denyUnclaimed\":true}}");
        assertEquals(LivingItemActivation.Result.ALLOW,
            LivingItemActivation.evaluate(stick, false, LivingItemActivation.Via.PLAYER));
    }

    @Test
    @DisplayName("⑤ 探针不改变原物品 —— 预判不得给物品打上 IS_LIVING")
    void probeDoesNotMutateOriginal() {
        ItemStack tnt = new ItemStack(Items.TNT);
        LivingItemManager.hasAnyFunctionFor(tnt);
        assertFalse(LivingItemManager.isLivingItem(tnt), "预判用了 copy，原物品必须保持未活化");
    }
}
