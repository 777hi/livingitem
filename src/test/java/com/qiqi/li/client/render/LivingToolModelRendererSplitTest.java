package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        com.qiqi.li.living.config.ToolVisualConfig.resetForTest();
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

    // ── 双身份动态分流（2026-11-13 回归）────────────────────────────────────
    // 两个身份都作为入参传入：FML 单测不加载 item tags，标签/活化判据在测试里恒 false（环境性），
    // 故这里用显式布尔模拟身份命中，钉住分流逻辑本身。

    @Test
    @DisplayName("双身份·无动作戳 ⇒ 待机仍算工具，不占攻击环")
    void dualIdentity_idle_staysTool() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);

        assertFalse(LivingToolModelRenderer.attackingWindow(sword, NOW, GAME_TIME));
        assertFalse(LivingToolModelRenderer.routeAssistToWeapon(sword, NOW, GAME_TIME, true, true),
            "待机双身份物品应留在挖掘/背后环，避免同一个在两环各画一次");
    }

    @Test
    @DisplayName("双身份·刚出手 ⇒ 临时划归武器列表（攻击环收得到输入）")
    void dualIdentity_freshAction_routesToAttackRing() {
        ItemStack sword = weaponWithAction(GAME_TIME, BlockPos.containing(5.5, 2.5, 5.5));

        assertTrue(LivingToolModelRenderer.attackingWindow(sword, NOW, GAME_TIME));
        assertTrue(LivingToolModelRenderer.routeAssistToWeapon(sword, NOW, GAME_TIME, true, true),
            "打怪时活剑/活斧必须进 assistWeapons，否则渲染端永远没有攻击环");
    }

    @Test
    @DisplayName("双身份·陈旧戳 / 空目标 ⇒ 回工具环；纯工具恒不进攻击环")
    void dualIdentity_staleAndPureTool_stayTool() {
        ItemStack stale = weaponWithAction(GAME_TIME - 100, BlockPos.containing(1, 2, 3));
        assertFalse(LivingToolModelRenderer.routeAssistToWeapon(stale, NOW, GAME_TIME, true, true));

        ItemStack nullTarget = weaponWithAction(GAME_TIME, null);
        assertFalse(LivingToolModelRenderer.routeAssistToWeapon(nullTarget, NOW, GAME_TIME, true, true));

        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        assertFalse(LivingToolModelRenderer.routeAssistToWeapon(pickaxe, NOW, GAME_TIME, false, true),
            "纯工具没有武器身份 ⇒ 恒走挖掘/背后环");
    }

    @Test
    @DisplayName("纯武器（非工具身份）⇒ 恒走攻击环路径，与是否出手无关")
    void pureWeapon_alwaysRoutesToWeapon() {
        ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);

        assertTrue(LivingToolModelRenderer.routeAssistToWeapon(weapon, NOW, GAME_TIME, true, false));
    }

    @Test
    @DisplayName("窗口判据同源：attackingWindow 与 splitByAction 结论必须一致")
    void windowCriteria_agreeWithSplit() {
        BlockPos target = BlockPos.containing(7.5, 3.5, 9.5);
        List<ItemStack> cases = List.of(
            weaponWithAction(GAME_TIME, target),         // 刚出手
            weaponWithAction(GAME_TIME - 100, target),   // 陈旧戳
            weaponWithAction(GAME_TIME, null),           // 空目标
            new ItemStack(Items.IRON_AXE));              // 无戳

        for (ItemStack weapon : cases) {
            boolean viaWindow = LivingToolModelRenderer.attackingWindow(weapon, NOW, GAME_TIME);
            boolean viaSplit = !LivingToolModelRenderer.splitByAction(
                List.of(weapon), NOW, GAME_TIME).attacking().isEmpty();
            assertEquals(viaSplit, viaWindow,
                "两处窗口判据必须同源，否则双身份物品进了武器列表却飞不到目标");
        }
    }

    // ── 配置化联动（2026-11-26）─────────────────────────────────────────────

    @Test
    @DisplayName("攻击环调长后必须能收回：簿记剪枝用 max(脉冲, 攻击环)")
    void longerAttackRing_stillExpires() {
        // 模拟用户把攻击环从 6 调到 20、脉冲保持 6
        com.qiqi.li.living.config.ToolVisualConfig.applyForTest(
            6, 20, 0.30F, 4, 20, 10.0F, 8, 1.77F, 21.0F);
        try {
            long actionTick = GAME_TIME;
            // 首次见到戳（无同步延迟）⇒ 已播时长 = 0
            double age0 = LivingToolModelRenderer.pulseElapsed(actionTick, NOW, actionTick);
            assertEquals(0.0, age0, 1.0E-6F);

            // 推进 10 tick：脉冲早结束但攻击环仍在窗口内 ⇒ 簿记不能被清掉
            double now10 = NOW + 10;
            LivingToolModelRenderer.prunePulseStarts(now10);
            assertEquals(10.0, LivingToolModelRenderer.pulseElapsed(actionTick, now10, actionTick + 10),
                1.0E-6, "起点若被提前清掉，下一帧会重冻成'刚出手'⇒ 环永远不收回");

            // 推进到 21 tick：超过攻击环窗口 ⇒ 簿记应被清掉、戳过期
            double now21 = NOW + 21;
            LivingToolModelRenderer.prunePulseStarts(now21);
            double age21 = LivingToolModelRenderer.pulseElapsed(actionTick, now21, actionTick + 21);
            // 剪枝后重算起点 = now - clamp(gameTime - 戳, 0, 20) = now - 20 ⇒ age 恰为 20（=窗口，不再算在窗口内）
            assertEquals(20.0, age21, 1.0E-6F, "清掉后重算应判为已过期");
            assertFalse(LivingToolModelRenderer.attackingWindow(
                weaponWithAction(actionTick, BlockPos.containing(1, 2, 3)), now21, actionTick + 21));
        } finally {
            com.qiqi.li.living.config.ToolVisualConfig.resetForTest();
        }
    }
}
