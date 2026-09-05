package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 记账跳变门控测试（v19）。
 *
 * <p>锁定三件事：
 * <ol>
 *   <li>域活着但本 tick 无跳变 → 产出 0（修复停机虚能量：域存活窗口内不得白拿）；</li>
 *   <li>能量 = 合因子 × P × 本 tick 跳变路数，跳变按 offset 去重
 *       （同一振荡器被两条边看到只算 1 跳）；</li>
 *   <li>频率中性化回归：4t 与 12t（= 4 的 3 倍谐波，调谐效率公式对整数倍周期
 *       恰好给 1.0）在相同窗口内总发电量相等——旧「每 tick 无条件入账 ×P」下
 *       慢时钟会按 P 线性碾压快时钟。</li>
 * </ol>
 *
 * <p>驱动方式：直接注入 {@link PhaseEvent}（无 tracker 收敛等待），
 * 逐 tick 调用 {@code accountEnergy} 并 {@code drainAndEndTick} 累计产出。</p>
 */
class AccountingGateTest {

    private static final int HIGH = 4096;   // |Δ| = √4096 = 64

    /** 单振荡器场景：周期 {@code period}，事件落在 t ≡ 0 (mod period) 且 t ≤ lastEventTick，逐 tick 记账并累计产出 */
    private static long runOscillator(int pref, int period, int ticks, int lastEventTick) {
        ContainerPowerData data = new ContainerPowerData();
        GeneratorState gen = data.getOrCreateGenerator(0);
        gen.setPreferredPeriodFromStack(pref);
        long totalRe = 0;
        for (long t = 0; t < ticks; t++) {
            if (t <= lastEventTick && t % period == 0) {
                gen.channel().onPhaseEvent(
                    new PhaseEvent(0, period, (int) (t % period), HIGH, t), gen.preferredPeriod());
            }
            LivingWaxedCopperFunction.accountEnergy(gen, gen.channel(), gen.preferredPeriod(), null, -1, t);
            totalRe += gen.drainAndEndTick();
        }
        return totalRe;
    }

    @Test
    @DisplayName("域活着但本 tick 无跳变 → 产出 0（停机不虚发）")
    void noJumpTick_producesNothing() {
        ContainerPowerData data = new ContainerPowerData();
        GeneratorState gen = data.getOrCreateGenerator(0);
        gen.setPreferredPeriodFromStack(4);

        // t=0：一次跳变 → 有产出
        gen.channel().onPhaseEvent(new PhaseEvent(0, 4, 0, HIGH, 0), 4);
        LivingWaxedCopperFunction.accountEnergy(gen, gen.channel(), 4, null, -1, 0);
        assertTrue(gen.drainAndEndTick() > 0, "跳变 tick 应有产出");

        // t=1..30：无跳变。旧实现在域存活窗口内每 tick 白拿合因子×P，门控后必须为 0
        for (long t = 1; t <= 30; t++) {
            LivingWaxedCopperFunction.accountEnergy(gen, gen.channel(), 4, null, -1, t);
            assertEquals(0, gen.drainAndEndTick(), "无跳变 tick（t=" + t + "）必须产出 0");
        }
    }

    @Test
    @DisplayName("长期运行后停机：总产出不再增长（域存活窗口 ≠ 免费窗口）")
    void stoppedOscillator_totalFreezes() {
        long whileRunning = runOscillator(4, 4, 33, 32);   // 事件在 0,4,...,32，共 9 次
        long afterStop = runOscillator(4, 4, 70, 32);      // t=33 起再无事件（域仍存活至 ~t=64）

        assertEquals(whileRunning, afterStop,
            "停机后 37 个 tick（远超 EMA/域存活窗口）总产出不应有任何增长");
    }

    @Test
    @DisplayName("跳变按 offset 去重：同振荡器两条边 = 1 跳；两路异相 = 2 跳")
    void jumpCount_deduplicatesByOffset() {
        // 两条边（不同 sourceId）看到同一振荡器：同周期同偏移 → 去重为 1 跳
        ContainerPowerData data = new ContainerPowerData();
        GeneratorState gen = data.getOrCreateGenerator(0);
        gen.setPreferredPeriodFromStack(4);
        ChannelState ch = gen.channel();
        ch.onPhaseEvent(new PhaseEvent(0, 4, 0, HIGH, 0), 4);
        ch.onPhaseEvent(new PhaseEvent(1, 4, 0, HIGH, 0), 4);

        ChannelState.PhaseDomain active = ch.bestActiveDomain(4, 0);
        assertEquals(1, active.jumpCount(0), "同偏移的两条边应去重为 1 跳");
        assertEquals(1, active.n(), "同偏移合并 n=1");

        LivingWaxedCopperFunction.accountEnergy(gen, ch, 4, null, -1, 0);
        long expected = PowerMath.eventEnergyRe(Math.pow(64, 1.25), 4);
        assertEquals(expected, gen.drainAndEndTick(), "1 跳产出 = 合因子 × P（不因多边重复计）");

        // 两路异相（offset 0 与 1）→ 2 跳
        ContainerPowerData data2 = new ContainerPowerData();
        GeneratorState gen2 = data2.getOrCreateGenerator(0);
        gen2.setPreferredPeriodFromStack(4);
        ChannelState ch2 = gen2.channel();
        ch2.onPhaseEvent(new PhaseEvent(0, 4, 0, HIGH, 0), 4);
        ch2.onPhaseEvent(new PhaseEvent(1, 4, 1, HIGH, 0), 4);
        assertEquals(2, ch2.bestActiveDomain(4, 0).jumpCount(0), "两个不同偏移各算 1 跳");
    }

    @Test
    @DisplayName("频率中性化：4t 与 12t（3× 谐波）同窗口总发电量相等")
    void frequencyNeutrality_4t_vs_12tHarmonic() {
        // 12 = 4 × 3：调谐效率对整数倍周期恰好为 1.0（谐波别名），
        // 因此两台发电机（都堆 4 叠）的合因子相同；门控后产出只差跳变频率，
        // 12 tick 窗口内：4t 跳 3 次 × (F×4)  =  12t 跳 1 次 × (F×12)。
        long fast = runOscillator(4, 4, 12, 11);
        long slow = runOscillator(4, 12, 12, 11);

        double factor = Math.pow(64, 1.25);   // n=1、eff=1、u=0.25
        assertEquals(Math.round(factor * 4) * 3, fast, "4t：3 跳 × 合因子×4");
        assertEquals(Math.round(factor * 12), slow, "12t：1 跳 × 合因子×12");
        assertEquals(fast, slow, "频率中性化：整数倍谐波不得带来任何收益（旧实现慢钟 ×3）");
    }
}
