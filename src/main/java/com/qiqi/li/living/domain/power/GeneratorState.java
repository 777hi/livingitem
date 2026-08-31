package com.qiqi.li.living.domain.power;

/**
 * 单台发电机的发电状态（按槽位索引）。
 *
 * <p>v3 简化：不再按方向分组，单通道接收铜块网络的全部相位事件。
 * 形态由 {@link LivingWaxedCopperFunction} 的过滤策略决定。</p>
 */
public class GeneratorState {

    /** 偏好周期 = 堆叠数（§3.4 因子二）；0 表示未设置（<2 即宽带态） */
    private int preferredPeriod;

    /** 单通道 —— 接收铜块网络的全部相位事件 */
    private final ChannelState channel = new ChannelState();

    public ChannelState channel() {
        return channel;
    }

    public int preferredPeriod() {
        return preferredPeriod;
    }

    /** 偏好周期 = 堆叠数，clamp [0, 64]（堆 1 叠 = 宽带，见 §3.4 因子二） */
    public void setPreferredPeriodFromStack(int stackCount) {
        this.preferredPeriod = Math.max(0, Math.min(64, stackCount));
    }
}