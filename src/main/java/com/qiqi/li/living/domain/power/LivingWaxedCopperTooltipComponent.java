package com.qiqi.li.living.domain.power;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * 活涂蜡发电机的仪器面板数据（tooltip 底部由 {@code LivingWaxedCopperTooltipRenderer} 绘制）。
 *
 * <p>纯数据载体，服务端构建、随 tooltip 组件传到客户端渲染。
 * 全部取自发电机仪表盘 {@link LivingWaxedGeneratorData}，不额外引入世界状态。</p>
 *
 * @param phaseCount   相数 n（多相交变信号；示波器左半区据此画 n 条相位错开的正弦波）
 * @param period       检测周期 P（tick）；0 表示无信号/检测中
 * @param levelPower   各锈蚟级基础出力 EMA（RE/t），长度 = {@code PowerMath.OXIDATION_LEVELS}
 * @param activeLevels 活跃锈级数 N（1~4；0 表示无发电）
 */
public record LivingWaxedCopperTooltipComponent(
    int phaseCount,
    int period,
    List<Long> levelPower,
    int activeLevels
) implements TooltipComponent {

    /**
     * 从发电机仪表盘数据构建仪器面板数据。波形所需的相数/周期直接取自最佳域字段，
     * 不再依赖各偏移的 |Δ| 列表（那是离散柱状图数据源，无法体现连续波形形状）。
     */
    public static LivingWaxedCopperTooltipComponent from(LivingWaxedGeneratorData t) {
        return new LivingWaxedCopperTooltipComponent(
            t.phaseCount(), t.detectedPeriod(), t.levelPower(), t.activeLevels());
    }
}
