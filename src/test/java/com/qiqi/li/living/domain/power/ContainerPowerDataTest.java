package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红电发电状态机测试（v3 —— 相位事件总线）。
 *
 * <p>驱动方式：直接注入 {@link PhaseEvent}，无 Minecraft 依赖。
 * 公式 v3：eff_δ_sum = Σ√|Δ_i|，u = eff × (n / 偏好周期)，合因子 = (eff_δ_sum)^(1+u)。</p>
 */
class ContainerPowerDataTest {

    private static final int HIGH = 4096;

    /**
     * 模拟多路振荡器：按 tick 顺序喂入 PhaseEvent，返回能量统计。
     */
    private static long feedPhaseEvents(ContainerPowerData data, int slot, int pathCount,
                                        int preferredPeriod, int phaseStep, int period, int ticks) {
        GeneratorState gen = data.getOrCreateGenerator(slot);
        gen.setPreferredPeriodFromStack(preferredPeriod);
        ChannelState channel = gen.channel();

        long maxRe = 0;
        for (int t = 0; t < ticks; t++) {
            for (int p = 0; p < pathCount; p++) {
                int offset = p * phaseStep;
                int value = square(t, offset, period, HIGH);
                int prevValue = square(t - 1, offset, period, HIGH);
                if (value > 0 && prevValue <= 0) {
                    // 上升沿 → PhaseEvent
                    channel.onPhaseEvent(
                        new PhaseEvent(p, period, t % period, HIGH, t),
                        gen.preferredPeriod());
                    // 能量入账（v18：容器级无总账，只算数值用于断言）
                    double factor = channel.factorFor(period, gen.preferredPeriod());
                    if (factor > 0) {
                        long re = PowerMath.eventEnergyRe(factor, period);
                        maxRe = Math.max(maxRe, re);
                    }
                }
            }
        }
        return maxRe;
    }

    /** 50% 占空比方波 */
    private static int square(int tick, int phase, int period, int high) {
        return Math.floorMod(tick - phase, period) < period / 2 ? high : 0;
    }

    @Test
    @DisplayName("单路 4t 方波：n=1、eff_δ_sum=64、解锁度 0.25 → 合因子 = 64^1.25")
    void singlePath_squareWave_locksOntoPeriod() {
        ContainerPowerData data = new ContainerPowerData();
        feedPhaseEvents(data, 0, 1, 4, 0, 4, 14);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.channel();
        assertEquals(4, channel.bestPeriod(4));
        assertEquals(1, channel.bestN(4));
        assertEquals(64.0, channel.bestEffDeltaSum(4), 1e-9);

        // u = 1.0 * 1/4 = 0.25, factor = 64^1.25
        double expectedFactor = Math.pow(64, 1.25);
        assertEquals(expectedFactor, channel.bestFactor(4), 1e-3);
    }

    @Test
    @DisplayName("三相 6t（堆 8 叠，偏好 8t）：n=3、eff=0.5、解锁度 0.1875 → 合因子 = 192^1.1875")
    void threePhase6t_partialUnlock() {
        ContainerPowerData data = new ContainerPowerData();
        feedPhaseEvents(data, 0, 3, 8, 2, 6, 20);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.channel();

        assertEquals(6, channel.bestPeriod(8));
        assertEquals(3, channel.bestN(8));
        double effDeltaSum = 3 * Math.sqrt(HIGH);  // 3 * 64 = 192
        assertEquals(effDeltaSum, channel.bestEffDeltaSum(8), 1e-9);

        // eff = tuningEfficiency(|6-8|, 8) = tuningEfficiency(2, 8)
        double eff = PowerMath.tuningEfficiency(2, 8);
        double unlock = eff * 3 / 8;
        double expectedFactor = Math.pow(192, 1.0 + unlock);
        assertEquals(expectedFactor, channel.bestFactor(8), 1e-3);
    }

    @Test
    @DisplayName("五相 5t 满相（堆 5 叠）：n=5、完美调谐 → 解锁度 1.0 → 合因子 320^2 = 102400")
    void fivePhase5t_fullUnlock() {
        ContainerPowerData data = new ContainerPowerData();
        long maxRe = feedPhaseEvents(data, 0, 5, 5, 1, 5, 30);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.channel();

        assertEquals(5, channel.bestN(5));
        assertEquals(5, channel.bestPeriod(5));
        double effDeltaSum = 5 * Math.sqrt(HIGH);  // 5 * 64 = 320
        assertEquals(effDeltaSum, channel.bestEffDeltaSum(5), 1e-9);

        // u = 1.0 * 5/5 = 1.0, factor = 320^2 = 102400
        double expectedFactor = Math.pow(320, 2);
        assertEquals(expectedFactor, channel.bestFactor(5), 1e-3);

        // RE = 102400 * 5 = 512000
        assertEquals(512000L, maxRe);
    }

    @Test
    @DisplayName("无有效周期信号 → 无域（factor=0）")
    void junkTapping_excludedFromPhaseDomain() {
        ContainerPowerData data = new ContainerPowerData();
        GeneratorState gen = data.getOrCreateGenerator(0);
        gen.setPreferredPeriodFromStack(5);
        ChannelState channel = gen.channel();

        // 乱序的非周期事件 → 不会形成稳定域
        channel.onPhaseEvent(new PhaseEvent(0, 2, 0, 64, 0), gen.preferredPeriod());
        channel.onPhaseEvent(new PhaseEvent(0, 7, 3, 128, 3), gen.preferredPeriod());
        channel.onPhaseEvent(new PhaseEvent(0, 3, 1, 256, 13), gen.preferredPeriod());

        // 可能有多个域但 n 都很小
        // 关键是 factor 不会巨大
        double factor = channel.bestFactor(5);
        assertTrue(factor < 1000);
    }

    @Test
    @DisplayName("同相合并：两路完全相同的方波 → 同偏移去重 n=1")
    void samePhase_pathsMerge() {
        ContainerPowerData data = new ContainerPowerData();
        GeneratorState gen = data.getOrCreateGenerator(0);
        gen.setPreferredPeriodFromStack(4);
        ChannelState channel = gen.channel();

        // 两路 4t 同偏移 0
        for (int t = 0; t <= 16; t += 4) {
            channel.onPhaseEvent(new PhaseEvent(0, 4, 0, HIGH, t), gen.preferredPeriod());
            channel.onPhaseEvent(new PhaseEvent(1, 4, 0, HIGH, t), gen.preferredPeriod());
        }

        assertEquals(1, channel.bestN(4));
        // factor = 64^1.25 （同单路）
        double expected = Math.pow(64, 1.25);
        assertEquals(expected, channel.bestFactor(4), 1e-3);
    }

    @Test
    @DisplayName("锈级 EMA 账本（v18）：平滑 + K=1/16 边界换算，按锈级读取")
    void ledger_emaAndFeConversion() {
        ContainerPowerData data = new ContainerPowerData();
        data.updateOxidationEma(new long[]{18000, 0, 0, 0});
        // EMA = 18000 × 0.125 = 2250 RE/t → FE = 2250 × 1/16 = 140.625 → 取整
        assertTrue(data.getLevelEmaPowerFe(0) > 0);
        assertEquals(0, data.getLevelEmaPowerFe(1));
    }
}