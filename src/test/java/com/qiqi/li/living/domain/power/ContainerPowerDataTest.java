package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红电发电状态机测试 —— 用例直接取自 docs/红电波形分析表.md 的分析场景。
 *
 * <p>驱动方式：纯事件喂入（tick, 值），无 Minecraft 依赖。
 * 公式 v2：(log₂|Δ| × n)^(1+u) × P，u = eff × n / 偏好周期。</p>
 */
class ContainerPowerDataTest {

    private static final int HIGH = 4096;

    /** 50% 占空比整周期方波（period 为奇数时高电平 period/2 取整） */
    private static int square(int tick, int phase, int period, int high) {
        return Math.floorMod(tick - phase, period) < period / 2 ? high : 0;
    }

    /**
     * 按 tick 顺序喂多路方波（相位间隔 phaseStep），返回单次跳变最大 RE。
     * 注意必须 tick-major 遍历（与真实 glue 一致），通道的合并上升沿序列才单调。
     */
    private static long feedSquares(ContainerPowerData data, int slot, int pathCount,
                                    int preferredPeriod, int phaseStep, int period, int ticks) {
        GeneratorState gen = data.getOrCreateGenerator(slot);
        gen.setPreferredPeriodFromStack(preferredPeriod);
        ChannelState channel = gen.primaryChannel();
        while (channel.pathCount() < pathCount) channel.addPath();

        long maxRe = 0;
        for (int t = 0; t < ticks; t++) {
            for (int p = 0; p < pathCount; p++) {
                int value = square(t, p * phaseStep, period, HIGH);
                int delta = channel.onPathValue(p, t, value);
                if (delta == 0) continue;
                int absDelta = Math.abs(delta);
                double factor = channel.factorFor(p, gen.preferredPeriod(), absDelta);
                long re = PowerMath.eventEnergyRe(factor,
                    channel.path(p).periodTicks());
                data.onEventEnergy(re);
                maxRe = Math.max(maxRe, re);
            }
        }
        return maxRe;
    }

    @Test
    @DisplayName("单路 4t 方波：n=1，解锁度 0.25 → 合因子 = 12^1.25 ≈ 22.3")
    void singlePath_squareWave_locksOntoPeriod() {
        ContainerPowerData data = new ContainerPowerData();
        feedSquares(data, 0, 1, 4, 0, 4, 14);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();
        PathState path = channel.path(0);

        assertEquals(4.0, path.periodTicks(), 1e-9);
        assertTrue(path.hasUsablePhase());
        assertEquals(1, path.domainN());
        // log₂(4096)=12, n=1, unlock=1.0*1/4=0.25 → factor=12^1.25 ≈ 22.3
        double factor = channel.factorFor(0, gen.preferredPeriod(), HIGH);
        assertEquals(Math.pow(12, 1.25), factor, 1e-3);
    }

    @Test
    @DisplayName("三相 6t（堆 8 叠，偏好 8t）：n=3、eff=0.5、解锁度 0.1875 → 合因子 = 36^1.1875")
    void threePhase6t_partialUnlock() {
        ContainerPowerData data = new ContainerPowerData();
        feedSquares(data, 0, 3, 8, 2, 6, 20);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();

        assertEquals(6.0, channel.path(0).periodTicks(), 1e-6);
        assertEquals(3, channel.path(0).domainN());
        // log₂(4096)=12, n=3, eff=tuningEfficiency(2,8)=0.5, unlock=0.5*3/8=0.1875
        // factor = (12*3)^(1+0.1875) = 36^1.1875
        double factor = channel.factorFor(0, gen.preferredPeriod(), HIGH);
        assertEquals(Math.pow(36, 1.1875), factor, 1e-3);
    }

    @Test
    @DisplayName("五相 5t 满相（堆 5 叠）：n=5、完美调谐 → 解锁度 1.0 → 合因子 ×3600，单跳 RE=18000")
    void fivePhase5t_fullUnlock() {
        ContainerPowerData data = new ContainerPowerData();
        long maxRe = feedSquares(data, 0, 5, 5, 1, 5, 30);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();

        assertEquals(5, channel.path(0).domainN());
        // log₂(4096)=12, n=5, eff=1.0, unlock=1.0*5/5=1.0
        // factor = (12*5)^(1+1) = 60^2 = 3600
        double factor = channel.factorFor(0, gen.preferredPeriod(), HIGH);
        assertEquals(3600.0, factor, 1e-9);
        // RE = 3600 * 5 = 18000
        assertEquals(18000L, maxRe);
    }

    @Test
    @DisplayName("乱按的杂讯路：不进相位域（factor=0）")
    void junkTapping_excludedFromPhaseDomain() {
        ContainerPowerData data = new ContainerPowerData();
        GeneratorState gen = data.getOrCreateGenerator(0);
        gen.setPreferredPeriodFromStack(5);
        ChannelState channel = gen.primaryChannel();
        channel.addPath();

        long[][] events = {
            {0, HIGH}, {1, 0}, {3, HIGH}, {4, 0}, {13, HIGH}, {15, 0},
            {17, HIGH}, {18, 0}, {37, HIGH}, {38, 0}, {39, HIGH}
        };
        for (long[] e : events) {
            channel.onPathValue(0, e[0], (int) e[1]);
        }

        PathState path = channel.path(0);
        assertTrue(!path.hasUsablePhase());
        assertEquals(0.0, channel.factorFor(0, gen.preferredPeriod(), HIGH), 1e-9);
    }

    @Test
    @DisplayName("同相合并：两路完全相同的方波 → n=1，不奖励")
    void samePhase_pathsMerge() {
        ContainerPowerData data = new ContainerPowerData();
        feedSquares(data, 0, 2, 4, 0, 4, 14);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();
        assertEquals(1, channel.path(0).domainN());
        // 同相合并 n=1，解锁度=1.0*1/4=0.25
        double factor = channel.factorFor(0, gen.preferredPeriod(), HIGH);
        assertEquals(Math.pow(12, 1.25), factor, 1e-3);
    }

    @Test
    @DisplayName("RE 账本：EMA 平滑 + K=1/16 边界换算")
    void ledger_emaAndFeConversion() {
        ContainerPowerData data = new ContainerPowerData();
        data.onEventEnergy(18000);
        data.endTick(data.drainGeneratedRe());
        // EMA = 18000 × 0.125 = 2250 RE/t → FE = 2250 × 1/16 = 140.625
    }
}