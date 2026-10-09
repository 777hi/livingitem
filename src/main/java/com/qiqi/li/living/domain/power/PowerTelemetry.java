package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DomainSnapshot;

/**
 * 红电遥测快照与展示格式化。
 *
 * <p>从 {@code LivingWaxedCopperFunction} 拆出（2026-10-09 power 收口 步骤 4 第 5 刀）：
 * 只把既有账本状态投影为客户端遥测数据，不参与 tick 计算，也不修改发电状态。</p>
 */
final class PowerTelemetry {
    private static final int TELEMETRY_SIG_FIGS = 3;

    private PowerTelemetry() {}

    /**
     * 从发电机状态构建检测仪表盘快照（纯逻辑，可单测）。
     *
     * @param oxidation 该发电机所属锈蚀级（0~3）——容器级读数口径：本锈级 EMA 功率
     */
    static LivingWaxedGeneratorData buildTelemetry(
            GeneratorState gen, int stackCount, int coilForm, int oxidation, ContainerPowerData powerData) {
        ChannelState channel = gen.channel();
        int pref = gen.preferredPeriod();
        int bestPeriod = channel.bestPeriod(pref);
        int bestN = channel.bestN(pref);
        int bestDelta = channel.bestDelta();
        double effDeltaSum = channel.bestEffDeltaSum(pref);

        // 全部域快照（F3+H 显示用；v19 单通道——切制双通道已随相位解读重构退役）
        List<DomainSnapshot> domainSnapshots = new ArrayList<>();
        collectDomains(channel, domainSnapshots);

        // 每个发电机独立显示均值功率（窗口均值，无逐 tick 纹波；毫 FE 定点）+ 本锈级显示功率
        long emaFe = gen.getDisplayEmaPowerMilliFe();
        long levelEmaFe = powerData != null ? powerData.getLevelDisplayEmaPowerMilliFe(oxidation) : 0;

        // 网络级共振（容器级，§3.6.1）：只读 powerData 的 EMA 基础值，绝不回灌
        double resonanceGain = powerData != null ? PowerMath.quantize(powerData.resonanceGain(), TELEMETRY_SIG_FIGS) : 1.0;
        double resonanceBalance = powerData != null ? PowerMath.quantize(powerData.resonanceBalance(), TELEMETRY_SIG_FIGS) : 0.0;
        int activeLevels = powerData != null ? powerData.activeOxidationLevels() : 0;
        List<Long> levelPower = (powerData != null)
            ? toLevelPowerMilliFeList(powerData.getDisplayEmaByOxidationMilliFe())
            : List.of(0L, 0L, 0L, 0L);

        if (bestN <= 0) {
            return new LivingWaxedGeneratorData(
                0, 0, 0, 0, 0, coilForm, emaFe, levelEmaFe, domainSnapshots,
                resonanceGain, resonanceBalance, activeLevels, levelPower);
        }
        double eff = PowerMath.tuningEfficiency(
            Math.abs(bestPeriod - pref), pref);
        double unlock = Math.min(1.0, eff * bestN / pref);
        int unlockPermille = (int) Math.round(unlock * 1000);
        int effDeltaSumPermille = (int) Math.round(effDeltaSum * 1000);
        return new LivingWaxedGeneratorData(
            bestPeriod, bestN, unlockPermille, bestDelta, effDeltaSumPermille,
            coilForm, emaFe, levelEmaFe, domainSnapshots,
            resonanceGain, resonanceBalance, activeLevels, levelPower);
    }

    /** long[]（各锈级显示均值功率，毫 FE 定点）→ List<Long>（锈级柱状图数据源） */
    private static List<Long> toLevelPowerMilliFeList(long[] milliFe) {
        List<Long> out = new ArrayList<>(milliFe.length);
        for (long v : milliFe) out.add(v);
        return out;
    }

    /** 毫 FE 定点 → 人类可读功率串（≥1 FE 显示整数，否则两位小数） */
    static String formatMilliFe(long milliFe) {
        return milliFe >= 1000
            ? String.valueOf(milliFe / 1000)
            : String.format("%.2f", milliFe / 1000.0);
    }

    /**
     * 收集通道的全部域快照到 list。
     *
     * <p>相位必须走 {@code phasesSorted()} 而非 {@code deltaByOffset().values()}：
     * 后者是 HashMap 的值视图，顺序为哈希序，且会丢掉 offset 本身——
     * 客户端因此拿不到相位位置，相位圆盘无从画起。</p>
     */
    private static void collectDomains(ChannelState ch, List<DomainSnapshot> out) {
        for (var e : ch.domains().entrySet()) {
            ChannelState.PhaseDomain d = e.getValue();
            List<Integer> offsets = new ArrayList<>();
            List<Integer> deltas = new ArrayList<>();
            for (var phase : d.phasesSorted()) {
                offsets.add(phase.getKey());
                deltas.add(phase.getValue());
            }
            out.add(new DomainSnapshot(
                d.period(), d.n(), d.maxDelta(), PowerMath.quantize(d.effDeltaSum(), TELEMETRY_SIG_FIGS),
                offsets, deltas));
        }
    }
}
