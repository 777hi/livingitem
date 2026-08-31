package com.qiqi.li.living.domain.power;

/**
 * 相位事件 —— 振荡器在上升沿时广播到铜块网络。
 *
 * <p>发电机从网络接收全部事件，按 {@link #period()} 分域，
 * 域内按 {@link #offset()} 去重计 n，各偏移的 √|Δ| 求和计 eff_δ_sum。</p>
 *
 * @param sourceId 振荡器唯一标识（槽位索引）
 * @param period   振荡器周期（tick）
 * @param offset   在该周期内的相位偏移（0 ~ period-1，精确到 tick）
 * @param delta    跳变幅度 |Δ|（信号单位）
 * @param tick     事件发生时的游戏 tick
 */
public record PhaseEvent(
    long sourceId,
    int period,
    int offset,
    int delta,
    long tick
) {}