package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阶段二集成测试（v3 —— 相位事件总线）：单振荡器 → 发电机锁相 → RE 记账。
 *
 * <p>场景：单路 4t 周期上升沿信号 → 发电机应锁相到 4t 域并持续产出 RE。</p>
 */
class WaxedCopperOscillatorIT {

    @Test
    @DisplayName("单路 4t 振荡器 → 发电机：域锁相 4t、n=1、eff_δ_sum = √4096 = 64")
    void singleOscillator_chargesGenerator() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);
        ChannelState channel = gen.channel();

        // 模拟 4t 周期振荡器，上升沿在 tick 0, 4, 8, 12, 16，|Δ| = 4096
        for (int t = 0; t <= 16; t += 4) {
            channel.onPhaseEvent(new PhaseEvent(0, 4, t % 4, 4096, t), gen.preferredPeriod());
        }

        assertEquals(4, channel.bestPeriod(4));
        assertEquals(1, channel.bestN(4));
        assertEquals(64.0, channel.bestEffDeltaSum(4), 1e-9);

        // 合因子 = (64)^(1+u) = 64^1.25，u = 1.0 * 1/4 = 0.25
        double expectedFactor = Math.pow(64, 1.25);
        double actualFactor = channel.bestFactor(4);
        assertEquals(expectedFactor, actualFactor, 1e-3);

        // RE = factor × period = 64^1.25 × 4
        long expectedRe = PowerMath.eventEnergyRe(expectedFactor, 4);
        assertTrue(expectedRe > 0);
    }

    @Test
    @DisplayName("宽带（堆 1 叠）：偏好周期 0 → 调谐效率低但仍有基础发电")
    void broadband_tuning() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(1);   // 堆 1 叠 = 宽带
        ChannelState channel = gen.channel();

        // 4t 振荡器
        for (int t = 0; t <= 16; t += 4) {
            channel.onPhaseEvent(new PhaseEvent(0, 4, t % 4, 4096, t), gen.preferredPeriod());
        }

        assertTrue(channel.bestPeriod(1) > 0);
        // u = eff × 1/1 = eff，eff = tuningEfficiency(|4-1|, 1) = tuningEfficiency(3, 1)
        // 失谐下 eff 很低，但 factor 仍有基础值
        double factor = channel.bestFactor(1);
        assertTrue(factor > 0);
    }

    @Test
    @DisplayName("多路 4t 不同相位 → n=3、eff_δ_sum = 3×√4096 = 192")
    void threePhase4t_fullUnlock() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);
        ChannelState channel = gen.channel();

        // 3 路不同相位的 4t 振荡器（偏移 0, 1, 2）
        for (int t = 0; t <= 16; t += 4) {
            channel.onPhaseEvent(new PhaseEvent(0, 4, 0, 4096, t), gen.preferredPeriod());
            channel.onPhaseEvent(new PhaseEvent(1, 4, 1, 4096, t + 1), gen.preferredPeriod());
            channel.onPhaseEvent(new PhaseEvent(2, 4, 2, 4096, t + 2), gen.preferredPeriod());
        }

        assertEquals(4, channel.bestPeriod(4));
        assertEquals(3, channel.bestN(4));
        assertEquals(192.0, channel.bestEffDeltaSum(4), 1e-9);

        // u = 1.0 * 3/4 = 0.75
        // factor = 192^(1.75) = 192^1.75
        double expectedFactor = Math.pow(192, 1.75);
        assertEquals(expectedFactor, channel.bestFactor(4), 1e-3);
    }

    @Test
    @DisplayName("5 路 5t 完美信号 → 最大发电量验证")
    void fivePhase5t_fullUnlock() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(5);
        ChannelState channel = gen.channel();

        // 5 路 5t 不同相位（偏移 0~4），|Δ| = 4096
        for (int t = 0; t <= 20; t += 5) {
            for (int offset = 0; offset < 5; offset++) {
                channel.onPhaseEvent(new PhaseEvent(offset, 5, offset, 4096, t + offset),
                    gen.preferredPeriod());
            }
        }

        assertEquals(5, channel.bestPeriod(5));
        assertEquals(5, channel.bestN(5));
        double effDeltaSum = 5 * Math.sqrt(4096);   // 5 * 64 = 320
        assertEquals(320.0, channel.bestEffDeltaSum(5), 1e-9);

        // u = 1.0 * 5/5 = 1.0
        // factor = 320^(1+1) = 320^2 = 102400
        double expectedFactor = Math.pow(320, 2);
        double actualFactor = channel.bestFactor(5);
        assertEquals(expectedFactor, actualFactor, 1e-3);

        // RE = factor × 5 = 512000
        long expectedRe = PowerMath.eventEnergyRe(expectedFactor, 5);
        assertEquals(512000L, expectedRe);
    }

    @Test
    @DisplayName("多个周期域共存：4t 和 8t 信号各自独立")
    void multiplePeriodDomains() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);
        ChannelState channel = gen.channel();

        // 4t 振荡器（偏移 0, 2）
        for (int t = 0; t <= 16; t += 4) {
            channel.onPhaseEvent(new PhaseEvent(0, 4, 0, 4096, t), gen.preferredPeriod());
            channel.onPhaseEvent(new PhaseEvent(1, 4, 2, 4096, t + 2), gen.preferredPeriod());
        }
        // 8t 振荡器（偏移 1）
        for (int t = 0; t <= 16; t += 8) {
            channel.onPhaseEvent(new PhaseEvent(2, 8, 1, 2048, t + 1), gen.preferredPeriod());
        }

        // 最佳域应为 4t（n=2 最大，且最接近偏好周期 4）
        assertEquals(4, channel.bestPeriod(4));
        assertEquals(2, channel.bestN(4));
        // 4t 域 eff_δ_sum = 2×√4096 = 128
        assertEquals(128.0, channel.bestEffDeltaSum(4), 1e-9);
    }
}