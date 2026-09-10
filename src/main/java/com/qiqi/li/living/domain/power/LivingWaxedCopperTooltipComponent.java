package com.qiqi.li.living.domain.power;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * 活涂蜡发电机的仪器面板数据（tooltip 底部由 {@code LivingWaxedCopperTooltipRenderer} 绘制）。
 *
 * <p>纯数据载体，服务端构建、随 tooltip 组件传到客户端渲染。
 * 全部取自发电机仪表盘 {@link LivingWaxedGeneratorData}，不额外引入世界状态。</p>
 *
 * <p><b>相位圆盘数据源（v19.2）</b>：{@code phaseOffsets} / {@code phaseDeltas} 是
 * <strong>真实相位</strong>——每一路锁相波形在周期内的偏移 φᵢ 与跳变幅度 |Δᵢ|，
 * 二者一一对应、同按 φ 升序。旧版示波器画的是「n 条均匀错开、等幅、固定周期数」的
 * 装饰性正弦，与真实信号无任何数据连接，相数一多就挤成一团且读不出任何信息。</p>
 *
 * @param phaseCount      相数 n（多相交变信号）
 * @param period          检测周期 P（tick）；0 表示无信号/检测中
 * @param preferredPeriod 偏好周期（堆叠数）
 * @param levelPowerMilliFe 各锈蚟级显示均值功率（毫 FE 定点），长度 = {@code PowerMath.OXIDATION_LEVELS}
 * @param activeLevels    活跃锈级数 N（1~4；0 表示无发电）
 * @param unlockPermille  解锁度 u × 1000（0..1000）——圆盘外弧的填充比例
 * @param phaseOffsets    最佳域各相位偏移 φᵢ，升序
 * @param phaseDeltas     最佳域各相位 |Δᵢ|，与 {@code phaseOffsets} 同序
 * @param minPhaseGap     环形最小相位间隔（tick）；相位少于 2 路时为 -1
 */
public record LivingWaxedCopperTooltipComponent(
    int phaseCount,
    int period,
    int preferredPeriod,
    List<Long> levelPowerMilliFe,
    int activeLevels,
    int unlockPermille,
    List<Integer> phaseOffsets,
    List<Integer> phaseDeltas,
    int minPhaseGap
) implements TooltipComponent {

    /** 无相位数据时的空列表（无信号 / 遥测缺失） */
    private static final List<Integer> NO_PHASES = List.of();

    /**
     * 从发电机仪表盘数据构建仪器面板数据。
     *
     * <p>相位取自<b>最佳域</b>（{@link LivingWaxedGeneratorData#bestDomain()}）——即顶层
     * {@code detectedPeriod} / {@code phaseCount} 的来源域，保证圆盘与数字读数同口径。
     * 旧实现只传 |Δ| 值列表、把 offset 丢掉，是相位图画不出来的根因。</p>
     */
    public static LivingWaxedCopperTooltipComponent from(LivingWaxedGeneratorData t, int preferredPeriod) {
        var best = t.bestDomain();
        return new LivingWaxedCopperTooltipComponent(
            t.phaseCount(), t.detectedPeriod(), preferredPeriod, t.levelPowerMilliFe(), t.activeLevels(),
            t.unlockPermille(),
            best != null ? best.offsets() : NO_PHASES,
            best != null ? best.deltas() : NO_PHASES,
            best != null ? best.minPhaseGap() : -1);
    }
}