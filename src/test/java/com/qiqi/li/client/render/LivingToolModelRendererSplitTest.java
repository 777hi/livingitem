package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.tools.LivingToolAction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 攻击环分流的唯一判据（{@code splitByAction}）—— 三条环路径
 * （本机 {@code renderAttackRing} / 远程玩家 / 实体宿主）共用同一份。
 *
 * <p>2026-11-13 回归背景：实体宿主（女仆背包）路径曾整体只画静态背后环、不消费
 * {@code LivingToolAction} ⇒ 女仆补刀时环上武器纹丝不动。分流抽成一份后，
 * 三条路径的行为由本类钉住：只有【自己刚出手】的进攻击环（每把独立冷却互不带飞），
 * 环心取最新出手那把的目标；无动作戳 / 陈旧戳 / {@code target == null}（右键空气）
 * 都不算出手。</p>
 */
class LivingToolModelRendererSplitTest {

    private static final long GAME_TIME = 100L;
    private static final double NOW = 1000.0;

    @AfterEach
    void tearDown() {
        // 项目红线：静态状态必须显式 reset（Gradle 同一 JVM 跑全部测试）
        LivingToolModelRenderer.clearAnimationState();
    }

    private static ItemStack weaponWithAction(long tick, BlockPos target) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        LivingToolAction.set(stack, new LivingToolAction(tick, target));
        return stack;
    }

    @Test
    @DisplayName("无动作戳 ⇒ 全部待机，无攻击环目标")
    void noAction_allIdle() {
        List<ItemStack> weapons = List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_AXE));

        LivingToolModelRenderer.ActionSplit split =
            LivingToolModelRenderer.splitByAction(weapons, NOW, GAME_TIME);

        assertTrue(split.attacking().isEmpty());
        assertSame(weapons.get(0), split.idle().get(0));
        assertSame(weapons.get(1), split.idle().get(1));
        assertNull(split.target());
    }

    @Test
    @DisplayName("刚出手（戳 = gameTime）⇒ 进攻击环，目标 = 动作目标")
    void freshAction_attacking() {
        BlockPos target = BlockPos.containing(1.5, 2.5, 3.5);
        ItemStack weapon = weaponWithAction(GAME_TIME, target);

        LivingToolModelRenderer.ActionSplit split =
            LivingToolModelRenderer.splitByAction(List.of(weapon), NOW, GAME_TIME);

        assertSame(weapon, split.attacking().get(0));
        assertTrue(split.idle().isEmpty());
        assertEquals(target, split.target());
    }

    @Test
    @DisplayName("陈旧戳（超过 6t 窗口）⇒ 待机，不复活旧脉冲")
    void staleAction_idle() {
        ItemStack weapon = weaponWithAction(GAME_TIME - 100, BlockPos.containing(1, 2, 3));

        LivingToolModelRenderer.ActionSplit split =
            LivingToolModelRenderer.splitByAction(List.of(weapon), NOW, GAME_TIME);

        assertTrue(split.attacking().isEmpty());
        assertSame(weapon, split.idle().get(0));
        assertNull(split.target());
    }

    @Test
    @DisplayName("target == null 的动作戳（右键空气）不算出手")
    void nullTargetAction_idle() {
        ItemStack weapon = weaponWithAction(GAME_TIME, null);

        LivingToolModelRenderer.ActionSplit split =
            LivingToolModelRenderer.splitByAction(List.of(weapon), NOW, GAME_TIME);

        assertTrue(split.attacking().isEmpty());
        assertSame(weapon, split.idle().get(0));
        assertNull(split.target());
    }

    @Test
    @DisplayName("多把各打各的：冷却中的不跟飞，环心取最新出手那把的目标")
    void mixed_perWeaponCooldown_latestTargetWins() {
        BlockPos older = BlockPos.containing(10.5, 2.5, 10.5);
        BlockPos newer = BlockPos.containing(20.5, 2.5, 20.5);
        ItemStack cooling = weaponWithAction(GAME_TIME - 100, older);   // 先出手、已过窗
        ItemStack justStruck = weaponWithAction(GAME_TIME, newer);      // 刚出手

        LivingToolModelRenderer.ActionSplit split =
            LivingToolModelRenderer.splitByAction(List.of(cooling, justStruck), NOW, GAME_TIME);

        assertSame(justStruck, split.attacking().get(0));
        assertSame(cooling, split.idle().get(0));
        assertEquals(newer, split.target(), "环心 = 最新出手那把的目标");
    }

    @Test
    @DisplayName("脉冲缩放：窗内中段胀出（>1），过窗回落 1.0")
    void pulseScale_midWindowBulges_staleReturns() {
        // 正弦脉冲在窗内中段（age = 3）到峰值；出手瞬间 age = 0 恰为 1.0（起点不跳变）
        float midWindow = LivingToolModelRenderer.pulseScaleOf(GAME_TIME - 3, NOW, GAME_TIME);
        assertTrue(midWindow > 1.0F, "窗内中段应当胀出，实际 " + midWindow);

        float stale = LivingToolModelRenderer.pulseScaleOf(GAME_TIME - 100, NOW, GAME_TIME);
        assertEquals(1.0F, stale, 1.0E-6F);
    }
}
