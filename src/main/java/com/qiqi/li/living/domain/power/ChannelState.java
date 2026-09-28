package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 线圈通道 —— 相位事件域管理器（v3 —— 相位事件总线）。
 *
 * <p>从铜块网络接收 {@link PhaseEvent}，按周期分域，域内按偏移去重计 n，
 * 各偏移的 √|Δ| 求和计 eff_δ_sum。
 * 合因子 = (eff_δ_sum)^(1+u)，u = 调谐效率 × (n / 偏好周期)。</p>
 *
 * <p><b>跳变门控记账（v19）</b>：入账只看「本 tick 真实跳变的域」——
 * {@link #bestActiveDomain} 只在 {@code jumpTick == 当前 tick} 的域中选最佳，
 * 能量 = 合因子 × P × 本 tick 跳变路数。域活着但本 tick 无上升沿 → 产出 0。
 * 这把记账从「域活着就付」回归「跳变即能量事件」：修复每 tick 无条件入账
 * ×P 造成的频率中性化反转（慢时钟按 P 线性碾压快时钟）与停机虚能量
 * （域存活窗口内照常入账）。</p>
 *
 * <p><b>偏移活性（v18 修复）</b>：域内每个偏移各自记录「最近一次被观测到的 tick」，
 * {@link #tickCleanup} 会剪掉超期未再出现的偏移。这一条是正确性要求而非优化——
 * 若偏移只增不减，玩家拆掉某路振荡器后，只要同周期还有别的振荡器在跳，
 * 该域就永不超时，被拆掉那路的偏移会永久留在域内，n 被永久高估 →
 * 合因子虚高 → 长期白拿发电量。</p>
 */
public class ChannelState {

    /** 相位域：period → PhaseDomain */
    private final Map<Integer, PhaseDomain> domains = new HashMap<>();

    /** 静默超时下限（tick）：即便周期极短也至少容忍这么久，吸收周期估计抖动 */
    private static final int MIN_SILENCE_TIMEOUT = 32;
    /** 静默超时上限（tick，约 60s）：防止周期估计失控时域永不回收 */
    private static final int MAX_SILENCE_TIMEOUT = 1200;

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
        domain.addOffset(event.offset(), event.delta(), event.tick());
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
        return factorOf(domain, preferredPeriod);
    }

    /** 指定域的合因子（与 {@link #factorFor} 同口径，供门控记账对 bestActiveDomain 的结果直接取值） */
    public double factorOf(PhaseDomain domain, int preferredPeriod) {
        if (domain == null || domain.n() == 0) return 0;
        double eff = PowerMath.tuningEfficiency(
            Math.abs(domain.period - preferredPeriod), preferredPeriod);
        double unlock = Math.min(1.0, eff * domain.n() / preferredPeriod);
        return PowerMath.combinedFactor(domain.effDeltaSum(), domain.n(), unlock);
    }

    /**
     * 门控记账入口：取「本 tick 有跳变」的最佳域（无则 {@code null}）。
     *
     * <p>只在 {@code jumpTick == tick} 的域中按现有规则（n 最大，同 n 取周期最近）
     * 选择。域的历史状态（n / eff_δ_sum）仍反映全部存活相位——门控只决定
     * 「本 tick 有没有真实的跳变可以入账」，不改变域的质量评估口径。</p>
     *
     * @param preferredPeriod 发电机偏好周期
     * @param tick            当前 tick（跳变集合的归属 tick）
     */
    public PhaseDomain bestActiveDomain(int preferredPeriod, long tick) {
        PhaseDomain best = null;
        int bestN = -1;
        int bestDist = Integer.MAX_VALUE;
        for (var e : domains.entrySet()) {
            PhaseDomain d = e.getValue();
            if (d.jumpTick != tick || d.n() == 0) continue;
            int dist = Math.abs(d.period - preferredPeriod);
            if (d.n() > bestN || (d.n() == bestN && dist < bestDist)) {
                best = d;
                bestN = d.n();
                bestDist = dist;
            }
        }
        return best;
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
     * 清理过期域与过期偏移。
     *
     * <p>两级超时（同一阈值）：</p>
     * <ul>
     *   <li><b>域级</b>：超过阈值未收到任何事件 → 整域移除。</li>
     *   <li><b>偏移级</b>：某个偏移超过阈值未再出现 → 该偏移移除；
     *       域内偏移清空后整域一并移除。</li>
     * </ul>
     *
     * <p><b>阈值取 {@code max(32, max(偏好周期, 本域周期) × 2)}</b>，夹在
     * [{@value #MIN_SILENCE_TIMEOUT}, {@value #MAX_SILENCE_TIMEOUT}] 之间。</p>
     *
     * <p>为什么必须纳入<b>本域周期</b>：偏移每 {@code period} tick 才重现一次，
     * 若只看偏好周期（发电机堆叠数，可能远小于实际周期），长周期/稀疏网络
     * （如 P=130 只有 2 路，事件间隔 65 tick）两次正常心跳之间的间隙会被误判为
     * 「静默」→ 整域被删 → 每次重建 n 都从 1 重数，永远堆不起来。</p>
     *
     * <p>为什么<b>保留</b>偏好周期作为下限：偏好周期表达「这台发电机在听多慢的信号」，
     * 听慢信号时不该急着放弃。且取 max 意味着新阈值恒 ≥ 旧阈值，
     * 本次改动只可能延长存活、不可能缩短 —— 是单调的放宽，不会误伤现有配置。</p>
     *
     * @param currentTick     当前游戏 tick
     * @param preferredPeriod 发电机偏好周期（堆叠数），作为超时下限
     */
    public void tickCleanup(long currentTick, int preferredPeriod) {
        Iterator<Map.Entry<Integer, PhaseDomain>> it = domains.entrySet().iterator();
        while (it.hasNext()) {
            PhaseDomain d = it.next().getValue();

            long timeout = (long) Math.max(preferredPeriod, d.period) * 2L;
            timeout = Math.min(MAX_SILENCE_TIMEOUT, Math.max(MIN_SILENCE_TIMEOUT, timeout));

            if (currentTick - d.lastEventTick > timeout) {
                it.remove();
                continue;
            }

            d.pruneStaleOffsets(currentTick, timeout);

            if (d.n() == 0) {
                it.remove();
            }
        }
    }

    /** 深拷贝另一通道的全部相位域与最近事件缓存（供同组件发电机共享历史） */
    public void copyFrom(ChannelState other) {
        domains.clear();
        for (var e : other.domains.entrySet()) {
            PhaseDomain src = e.getValue();
            PhaseDomain dst = new PhaseDomain(src.period);
            for (var o : src.offsets.entrySet()) {
                dst.offsets.put(o.getKey(), new OffsetState(o.getValue().lastSeenTick, o.getValue().maxDelta));
            }
            dst.lastEventTick = src.lastEventTick;
            // 跳变门控状态随之同步：成员发电机与锚点看到同一份「本 tick 跳变」
            dst.jumpTick = src.jumpTick;
            dst.jumpedOffsets.addAll(src.jumpedOffsets);
            domains.put(e.getKey(), dst);
        }
        lastEventPeriod = other.lastEventPeriod;
        lastEventN = other.lastEventN;
        lastEventDelta = other.lastEventDelta;
        lastEventTick = other.lastEventTick;
    }

    /** 全部域只读视图（F3+H 显示用） */
    public Map<Integer, PhaseDomain> domains() {
        return Collections.unmodifiableMap(domains);
    }

    // ── PhaseDomain 内部类 ──

    /**
     * 单个相位域 —— 同一周期内所有振荡器的相位集合。
     *
     * <p>域内按偏移去重：同周期同偏移的多个振荡器合并为 1 路，
     * |Δ| 取该偏移的最大值。</p>
     *
     * <p>每个偏移额外记录最近出现 tick，供 {@link #pruneStaleOffsets} 剪掉
     * 已停摆的振荡器（否则 n 只增不减，见类注释）。</p>
     *
     * <p><b>跳变门控（v19）</b>：额外维护「最近一个事件 tick 内实际跳变的偏移集合」，
     * 供 {@link ChannelState#bestActiveDomain} 判定本 tick 是否有真实跳变可入账。
     * 集合按 tick 归零、按 offset 去重——同一振荡器被两条边看到只算 1 跳。</p>
     */
    public static class PhaseDomain {
        final int period;
        /** 偏移 → 该偏移的观测状态（最近出现 tick + 最大 |Δ|） */
        private final Map<Integer, OffsetState> offsets = new HashMap<>();
        long lastEventTick;

        /** 本 tick（= {@code jumpTick}）实际跳变的偏移集合 */
        private final java.util.HashSet<Integer> jumpedOffsets = new java.util.HashSet<>();
        /** 跳变集合所属的 tick（-1 = 尚无跳变） */
        private long jumpTick = -1;

        PhaseDomain(int period) {
            this.period = period;
        }

        void addOffset(int offset, int delta, long tick) {
            OffsetState st = offsets.get(offset);
            if (st == null) {
                offsets.put(offset, new OffsetState(tick, delta));
            } else {
                st.lastSeenTick = tick;
                if (delta > st.maxDelta) st.maxDelta = delta;
            }
            if (tick != jumpTick) {
                jumpedOffsets.clear();
                jumpTick = tick;
            }
            jumpedOffsets.add(offset);
        }

        /**
         * 本 tick 实际跳变的相位路数（按 offset 去重）。
         *
         * @param tick 当前 tick；本域最近一次跳变不在此 tick 则返回 0
         */
        public int jumpCount(long tick) {
            return jumpTick == tick ? jumpedOffsets.size() : 0;
        }

        /** 剪掉超期未再出现的偏移 */
        void pruneStaleOffsets(long currentTick, long offsetTimeout) {
            offsets.entrySet().removeIf(e -> currentTick - e.getValue().lastSeenTick > offsetTimeout);
        }

        /** 域内不同偏移数（= 相数 n） */
        public int n() {
            return offsets.size();
        }

        /** 域内最大 |Δ| */
        public int maxDelta() {
            int max = 0;
            for (OffsetState st : offsets.values()) {
                if (st.maxDelta > max) max = st.maxDelta;
            }
            return max;
        }

        /** Σ√|Δ_i|：各偏移的 √|Δ| 求和 */
        public double effDeltaSum() {
            double sum = 0.0;
            for (OffsetState st : offsets.values()) {
                sum += Math.sqrt(st.maxDelta);
            }
            return sum;
        }

        /** 域周期（tick） */
        public int period() {
            return period;
        }

        /** 各偏移的 |Δ| 映射（F3+H 显示用） */
        public Map<Integer, Integer> deltaByOffset() {
            Map<Integer, Integer> out = new HashMap<>();
            for (var e : offsets.entrySet()) {
                out.put(e.getKey(), e.getValue().maxDelta);
            }
            return Collections.unmodifiableMap(out);
        }

        /**
         * 域内各相位（offset → maxDelta），**按 offset 升序**。
         *
         * <p>凡是要求「顺序」的消费者（相位圆盘、F3+H 文本诊断、域快照序列化）都必须走这里：
         * {@link #deltaByOffset()} 内部是 HashMap，迭代顺序是哈希序而非 offset 序，
         * 直接遍历它拿到的顺序不确定——曾因此把 offset 整个丢掉，只传了 δ 值列表给客户端。</p>
         */
        public List<Map.Entry<Integer, Integer>> phasesSorted() {
            List<Map.Entry<Integer, Integer>> out = new ArrayList<>(offsets.size());
            for (var e : offsets.entrySet()) {
                out.add(Map.entry(e.getKey(), e.getValue().maxDelta));
            }
            out.sort(Map.Entry.<Integer, Integer>comparingByKey());
            return out;
        }
    }

    /** 单个偏移的观测状态：最近一次出现的 tick + 该偏移观测到的最大 |Δ| */
    private static final class OffsetState {
        long lastSeenTick;
        int maxDelta;

        OffsetState(long lastSeenTick, int maxDelta) {
            this.lastSeenTick = lastSeenTick;
            this.maxDelta = maxDelta;
        }
    }
}
