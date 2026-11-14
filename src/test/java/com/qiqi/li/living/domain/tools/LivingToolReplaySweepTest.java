package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;

/**
 * 假玩家出手「不做横扫」守卫（{@code LivingToolReplay#onSweepAttack}）。
 *
 * <p>回归背景（2026-11-14 用户实测）：女仆被自己背包活武器的横扫伤害打伤。
 * 出手原点 = 载体位置，原版横扫过滤只有 {@code isAlliedTo}（队伍制，表达不了载体
 * 关系，且 {@code LivingToolFakePlayer#isAlliedTo} 只豁免玩家）⇒ 载体必在横扫框内。
 * 收口 = 官方挂点 {@code SweepAttackEvent}：出手者是 {@code LivingToolFakePlayer}
 * 时整段关掉横扫（被扫伤害 / 扫击音效 / 横扫粒子同块关闭）；真玩家出手不受影响。</p>
 */
class LivingToolReplaySweepTest {

    @Test
    @DisplayName("假玩家出手 ⇒ 横扫关闭（被扫伤害/音效/粒子同块不触发）")
    void sweep_suppressedForLivingToolFakePlayer() {
        LivingToolFakePlayer fake = mock(LivingToolFakePlayer.class);
        SweepAttackEvent event = new SweepAttackEvent(fake, mock(Entity.class), true);

        LivingToolReplay.onSweepAttack(event);

        assertFalse(event.isSweeping(), "假玩家的出手不得横扫");
        assertTrue(event.isVanillaSweep(), "只关结果，不动原版判定记录");
    }

    @Test
    @DisplayName("真玩家出手 ⇒ 横扫保持原版行为")
    void sweep_keptForRealPlayers() {
        SweepAttackEvent event = new SweepAttackEvent(mock(Player.class), mock(Entity.class), true);

        LivingToolReplay.onSweepAttack(event);

        assertTrue(event.isSweeping(), "守卫只认假玩家，真玩家原样");
    }

    @Test
    @DisplayName("原版本就不横扫的出手 ⇒ 不被守卫改成横扫")
    void sweep_doesNotForceSweep() {
        LivingToolFakePlayer fake = mock(LivingToolFakePlayer.class);
        SweepAttackEvent event = new SweepAttackEvent(fake, mock(Entity.class), false);

        LivingToolReplay.onSweepAttack(event);

        assertFalse(event.isSweeping(), "守卫只关不开");
    }
}
