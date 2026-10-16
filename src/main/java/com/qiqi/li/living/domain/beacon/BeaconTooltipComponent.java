package com.qiqi.li.living.domain.beacon;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * 活信标 9 柱小金字塔面板数据（tooltip 底部由 {@code LivingBeaconTooltipRenderer} 绘制）。
 *
 * <p>纯数据载体，照 {@code LivingWaxedCopperTooltipComponent} 范式；全部取自
 * {@link BeaconSegment.BeaconRuntime} 遥测（等级 / 柱高每 tick 现算，严禁写组件）。</p>
 *
 * <p><b>不设 empty 态</b>：未激活（level=0）恰是面板最该出力的时刻（告诉玩家差多少能亮），
 * 与发电机「无信号 ⇒ 面板 0 高度」不同。遥测缺失时由挂载端直接不 add 本组件。</p>
 *
 * @param level        激活等级 0~4
 * @param columnHeights 9 列柱高（下标 {@link BeaconPyramid#CENTER} 为信标本列）
 * @param columnExists  9 列存在性（false = 越界列，画竖虚线而非缺口）
 */
public record BeaconTooltipComponent(
    int level,
    long[] columnHeights,
    boolean[] columnExists
) implements TooltipComponent {

    public static BeaconTooltipComponent from(BeaconSegment.BeaconRuntime rt) {
        return new BeaconTooltipComponent(rt.level(), rt.columnHeights(), rt.columnExists());
    }
}
