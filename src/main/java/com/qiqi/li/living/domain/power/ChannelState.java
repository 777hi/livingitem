package com.qiqi.li.living.domain.power;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 线圈通道 —— 相位事件域管理器（v3 —— 相位事件总线）。
 *
 * <p>从铜块网络接收 {@link PhaseEvent}，按周期分域，域内按偏移去重计 n，
 * 各偏移的 √|Δ| 求和计 eff_δ_sum。
 * 合因子 = (eff_δ_sum)^(1+u)，u = 调谐效率 × (n / 偏好周期)。</p>
 */
public class ChannelState {

    /** 相位域：period → PhaseDomain */
    private final Map<Integer, PhaseDomain> domains = new HashMap<>();

    // ── 最近一次事件缓存（给 Telemetry 用）──
    private int lastEventPeriod;
    private int lastEventN;
    private int lastEventDelta;
    private long lastEventTick = -1;

    /**
     * 接收一次相位事件。
     *
     * @param event           相位事件
     * @param preferredPeriod 发电机偏好周期（堆叠数）
     */
    public void onPhaseEvent(PhaseEvent event, int preferredPeriod) {
        PhaseDomain domain = domains.computeIfAbsent(event.period(), PhaseDomain::new);
        domain.addOffset(event.offset(), event.delta());
        domain.lastEventTick = event.tick();

        lastEventPeriod = event.period();
        lastEventN = domain.n();
        lastEventDelta = event.delta();
        lastEventTick = event.tick();
    }

    /**
     * 取指定周期域的合因子。
     *
     * @param period          域周期
     * @param preferredPeriod 发电机偏好周期
     * @return 合因子，域不存在或 n=0 则返回 0
     */
    public double factorFor(int period, int preferredPeriod) {
        PhaseDomain domain = domains.get(period);
        if (domain == null || domain.n() == 0) return 0;
        double eff = PowerMath.tuningEfficiency(
            Math.abs(period - preferredPeriod), preferredPeriod);
        double unlock = Math.min(1.0, eff * domain.n() / preferredPeriod);
        return PowerMath.combinedFactor(domain.effDeltaSum(), domain.n(), unlock);
    }

    /**
     * 取最佳域的合因子（用于 Telemetry：n 最大，同 n 取周期最近）。
     *
     * @param preferredPeriod 发电机偏好周期
     * @return 合因子，无域则 0
     */
    public double bestFactor(int preferredPeriod) {
        PhaseDomain best = bestDomain(preferredPeriod);
        if (best == null) return 0;
        double eff = PowerMath.tuningEfficiency(
            Math.abs(best.period - preferredPeriod), preferredPeriod);
        double unlock = Math.min(1.0, eff * best.n() / preferredPeriod);
        return PowerMath.combinedFactor(best.effDeltaSum(), best.n(), unlock);
    }

    /** 取最佳域（n 最大，同 n 取周期最近偏好周期） */
    public PhaseDomain bestDomain(int preferredPeriod) {
        PhaseDomain best = null;
        int bestN = -1;
        int bestDist = Integer.MAX_VALUE;
        for (var e : domains.entrySet()) {
            PhaseDomain d = e.getValue();
            if (d.n() == 0) continue;
            int dist = Math.abs(d.period - preferredPeriod);
            if (d.n() > bestN || (d.n() == bestN && dist < bestDist)) {
                best = d;
                bestN = d.n();
                bestDist = dist;
            }
        }
        return best;
    }

    /** 最佳域的周期；无域则 0 */
    public int bestPeriod(int preferredPeriod) {
        PhaseDomain d = bestDomain(preferredPeriod);
        return d == null ? 0 : d.period;
    }

    /** 最佳域的相数 n；无域则 0 */
    public int bestN(int preferredPeriod) {
        PhaseDomain d = bestDomain(preferredPeriod);
        return d == null ? 0 : d.n();
    }

    /** 最佳域的最大 |Δ|（用于 Telemetry 显示）；无域则 0 */
    public int bestDelta() {
        return lastEventDelta;
    }

    /** 最佳域的 eff_δ_sum */
    public double bestEffDeltaSum(int preferredPeriod) {
        PhaseDomain d = bestDomain(preferredPeriod);
        return d == null ? 0 : d.effDeltaSum();
    }

    /**
     * 清理过期域：超过 2 倍偏好周期 tick 未收到事件的域自动移除。
     *
     * @param currentTick     当前游戏 tick
     * @param preferredPeriod 发电机偏好周期
     */
    public void tickCleanup(long currentTick, int preferredPeriod) {
        int timeout = Math.max(32, preferredPeriod * 2);  // 至少 32 tick
        domains.values().removeIf(d -> currentTick - d.lastEventTick > timeout);
    }

    /** 全部域只读视图（F3+H 显示用） */
    public Map<Integer, PhaseDomain> domains() {
        return java.util.Collections.unmodifiableMap(domains);
    }

    // ── PhaseDomain 内部类 ──

    /**
     * 单个相位域 —— 同一周期内所有振荡器的相位集合。
     *
     * <p>域内按偏移去重：同周期同偏移的多个振荡器合并为 1 路，
     * |Δ| 取该偏移的最大值。</p>
     */
    public static class PhaseDomain {
        final int period;
        final Set<Integer> offsets = new HashSet<>();
        final Map<Integer, Integer> deltaByOffset = new HashMap<>();
        long lastEventTick;

        PhaseDomain(int period) {
            this.period = period;
        }

        void addOffset(int offset, int delta) {
            offsets.add(offset);
            deltaByOffset.merge(offset, delta, Math::max);
        }

        /** 域内不同偏移数（= 相数 n） */
        public int n() {
            return offsets.size();
        }

        /** 域内最大 |Δ| */
        public int maxDelta() {
            return deltaByOffset.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        /** Σ√|Δ_i|：各偏移的 √|Δ| 求和 */
        public double effDeltaSum() {
            return deltaByOffset.values().stream()
                .mapToDouble(Math::sqrt)
                .sum();
        }

        /** 域周期（tick） */
        public int period() {
            return period;
        }

        /** 各偏移的 |Δ| 映射（F3+H 显示用） */
        public Map<Integer, Integer> deltaByOffset() {
            return java.util.Collections.unmodifiableMap(deltaByOffset);
        }
    }
}