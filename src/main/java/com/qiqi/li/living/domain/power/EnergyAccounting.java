package com.qiqi.li.living.domain.power;

/**
 * 红电能量入账算法。
 *
 * <p>从 {@code LivingWaxedCopperFunction} 拆出（2026-10-09 power 收口 步骤 4 第 5 刀）：
 * 只消费通道状态并更新发电机 / 锈级基础账本，不承担 tick 编排。</p>
 */
final class EnergyAccounting {
    private EnergyAccounting() {}

    /**
     * 从通道最佳域计算能量并记入发电机（v18：容器级无总账，改走 baseReByOx 按锈级累加）。
     *
     * <p><b>跳变门控（v19）</b>：只从「本 tick 有跳变」的最佳域入账，能量 = 合因子 × P ×
     * 本 tick 跳变路数。域活着但本 tick 无上升沿 → 产出 0，防止慢时钟碾压快时钟及停机后白拿电。</p>
     *
     * <p>同时按锈蚀级累加共振前的基础出力到 {@code baseReByOx}；共振增益只在 tick 末统一套用一次。</p>
     */
    static void accountEnergy(GeneratorState gen, ChannelState channel, int pref,
            long[] baseReByOx, int oxidation, long now) {
        ChannelState.PhaseDomain active = channel.bestActiveDomain(pref, now);
        if (active == null) return;
        double factor = channel.factorOf(active, pref);
        int period = active.period();
        if (factor > 0 && period > 0) {
            long re = PowerMath.eventEnergyRe(factor, period) * active.jumpCount(now);
            if (re > 0) {
                gen.onEventEnergy(re);
                if (baseReByOx != null && oxidation >= 0 && oxidation < baseReByOx.length) {
                    baseReByOx[oxidation] += re;
                }
            }
        }
    }
}
