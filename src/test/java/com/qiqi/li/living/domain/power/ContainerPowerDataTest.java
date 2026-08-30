package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红电发电状态机测试 —— 用例直接取自 docs/红电波形分析表.md 的分析场景。
 *
 * <p>驱动方式：纯事件喂入（tick, 值），无 Minecraft 依赖。
 * 因子语义见 docs/红电系统.md §3.4（v17.4，双因子模型）。</p>
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
                double factor = channel.factorFor(p, gen.preferredPeriod());
                long re = PowerMath.eventEnergyRe(Math.abs(delta), factor,
                    channel.path(p).periodTicks());
                data.onEventEnergy(re);
                maxRe = Math.max(maxRe, re);
            }
        }
        return maxRe;
    }

    @Test
    @DisplayName("单路 4t 方波：周期估计 4、规律度 1、n=1 → 合因子 ×1（调谐无感）")
    void singlePath_squareWave_locksOntoPeriod() {
        ContainerPowerData data = new ContainerPowerData();
        feedSquares(data, 0, 1, 4, 0, 4, 14);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();
        PathState path = channel.path(0);

        assertEquals(4.0, path.periodTicks(), 1e-9);
        assertTrue(path.hasUsablePhase());
        assertEquals(1.0, channel.regularity(), 1e-9);
        assertEquals(1, path.domainN());
        assertEquals(1.0, channel.factorFor(0, gen.preferredPeriod()), 1e-9);
    }

    @Test
    @DisplayName("三相 6t（堆 8 叠，偏好 8t）：n=3、r=1、解锁度 0.5 → 合因子 3^1.5 ≈ ×5.2")
    void threePhase6t_partialUnlock() {
        ContainerPowerData data = new ContainerPowerData();
        feedSquares(data, 0, 3, 8, 2, 6, 20);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();

        assertEquals(6.0, channel.path(0).periodTicks(), 1e-6);
        assertEquals(3, channel.path(0).domainN());
        assertEquals(1.0, channel.regularity(), 1e-9);
        assertEquals(Math.pow(3, 1.5), channel.factorFor(0, gen.preferredPeriod()), 1e-3);
    }

    @Test
    @DisplayName("五相 5t 满相（堆 5 叠）：n=5、r=1、完美调谐 → 合因子 ×25，单跳 RE=|Δ|×25×5")
    void fivePhase5t_fullUnlock() {
        ContainerPowerData data = new ContainerPowerData();
        long maxRe = feedSquares(data, 0, 5, 5, 1, 5, 30);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();

        assertEquals(5, channel.path(0).domainN());
        assertEquals(1.0, channel.regularity(), 1e-9);
        assertEquals(25.0, channel.factorFor(0, gen.preferredPeriod()), 1e-9);
        // |Δ|=4096、合因子 25、P=5 → 512000 RE（与波形分析表演算一致）
        assertEquals(4096L * 25 * 5, maxRe);
    }

    @Test
    @DisplayName("乱按的杂讯路：不进相位域（factor=0），通道规律度被拉低")
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
        assertEquals(0.0, channel.factorFor(0, gen.preferredPeriod()), 1e-9);
        assertTrue(channel.regularity() < 0.6);
    }

    @Test
    @DisplayName("同相合并：两路完全相同的方波 → n=1，不奖励")
    void samePhase_pathsMerge() {
        ContainerPowerData data = new ContainerPowerData();
        feedSquares(data, 0, 2, 4, 0, 4, 14);

        GeneratorState gen = data.getGenerator(0);
        ChannelState channel = gen.primaryChannel();
        assertEquals(1, channel.path(0).domainN());
        assertEquals(1.0, channel.factorFor(0, gen.preferredPeriod()), 1e-9);
    }

    @Test
    @DisplayName("RE 账本：EMA 平滑 + K=1/16 边界换算")
    void ledger_emaAndFeConversion() {
        ContainerPowerData data = new ContainerPowerData();
        data.onEventEnergy(512000);
        data.endTick(data.drainGeneratedRe());
        // EMA = 512000 × 0.125 = 64000 RE/t → FE = 64000 × 1/16 = 4000
        assertEquals(4000, data.getEmaPowerFe());

        data.endTick(data.drainGeneratedRe());        // 本 tick 无发电 → EMA 衰减
        // 64000 × 0.875 = 56000 RE/t → FE = 3500
        assertEquals(3500, data.getEmaPowerFe());
    }
}
