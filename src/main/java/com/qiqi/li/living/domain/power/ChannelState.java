package com.qiqi.li.living.domain.power;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 线圈通道 —— 一组输入路的合成感应（形态 = 线圈分组，见 §3.4）。
 *
 * <p>通道内各路按「相位域」分组计 n（同周期 + 同相位偏移合并），
 * 规律度 r 取自通道合并上升沿序列的间隔模式一致度（best-shift，
 * v1 用间隔模式近似 §3.8 的双周期窗口重合度，值级比对留待 v2）。
 * 合因子 = n^(1 + 调谐效率 × r)。</p>
 */
public class ChannelState {

    /** 合并上升沿序列保留条数（8 个间隔够 best-shift L∈{1..4} 使用） */
    private static final int MAX_RISING = 9;
    private static final int MAX_SHIFT = 4;

    private final List<PathState> paths = new ArrayList<>();
    private final ArrayDeque<Long> risingTicks = new ArrayDeque<>();
    private double regularity;           // 0..1，初始 0（起振观测期，线性保底）

    /** 新增一路输入，返回其状态（调用方按路径序号喂值） */
    public PathState addPath() {
        PathState p = new PathState();
        paths.add(p);
        return p;
    }

    public int pathCount() {
        return paths.size();
    }

    public PathState path(int index) {
        return paths.get(index);
    }

    /** 当前规律度 r ∈ [0,1] */
    public double regularity() {
        return regularity;
    }

    /**
     * 某路发生值变化（每 tick 由 glue 喂入，事件驱动）。
     *
     * @return 有符号变化量（0 = 无变化）
     */
    public int onPathValue(int pathIndex, long tick, int value) {
        PathState path = paths.get(pathIndex);
        int delta = path.recordValue(tick, value);
        if (delta == 0) return 0;
        if (delta > 0) onRising(tick);
        return delta;
    }

    /** 该路当前一次跳变适用的合因子 n^(1+解锁度)；不可用路返回 0（杂讯不产出） */
    public double factorFor(int pathIndex, int preferredPeriod) {
        PathState path = paths.get(pathIndex);
        if (!path.hasUsablePhase()) return 0;
        int n = Math.max(1, path.domainN());
        double eff = PowerMath.tuningEfficiency(
            Math.abs(path.periodTicks() - preferredPeriod), preferredPeriod);
        double unlock = eff * regularity;
        return PowerMath.combinedFactor(n, unlock);
    }

    /** 上升沿：更新合并序列 → 规律度 + 相位域重算（都是 O(常数)） */
    private void onRising(long tick) {
        Long last = risingTicks.peekLast();
        if (last != null && tick <= last) return;   // 防乱序：合并序列必须单调
        risingTicks.addLast(tick);
        while (risingTicks.size() > MAX_RISING) risingTicks.pollFirst();
        recomputeRegularity();
        recountPhaseDomains(tick);
    }

    /** 相位新鲜度门槛：静默超过 128t（2× 最大偏好周期）的路自动退出相位域（防幻影 n） */
    private static final long STALE_PHASE_TICKS = 128;

    /**
     * 相位域重算（每次上升沿触发）：按整数量子周期分域，
     * 域内以上升沿 mod 周期的偏移去重——同相合并，n = 域内不同偏移数。
     * 参与条件：周期可用 + 波形窗口非静默（16t 内有信号）+ 上升沿新鲜（≤128t，
     * 防幻影 n——输入撤除后相位资格冻结导致的虚高）。
     */
    /** 每 tick 由 glue 调用（无上升沿也重算——撤路后 n 自动衰减，防幻影） */
    void recountPhaseDomains(long currentTick) {
        Map<Integer, List<PathState>> domains = new HashMap<>();
        for (PathState p : paths) {
            // 参与条件：周期可用 + 波形未静默（16t 内有信号）+ 上升沿新鲜（≤128t）
            if (!p.hasUsablePhase() || p.waveBits() == 0) continue;
            if (currentTick - p.lastEventTick() > STALE_PHASE_TICKS) continue;
            domains.computeIfAbsent(p.roundedPeriod(), key -> new ArrayList<>()).add(p);
        }
        for (List<PathState> domain : domains.values()) {
            int period = domain.get(0).roundedPeriod();
            long base = Long.MAX_VALUE;
            for (PathState p : domain) {
                base = Math.min(base, p.lastRisingTick());
            }
            Set<Integer> offsets = new HashSet<>();
            for (PathState p : domain) {
                offsets.add((int) ((p.lastRisingTick() - base) % period));
            }
            int n = offsets.size();
            for (PathState p : domain) {
                p.setDomainN(n);
            }
        }
    }

    /**
     * 规律度：合并上升沿间隔序列的 best-shift 自一致度。
     *
     * <pre>
     * rel(L) = mean(|iv[i] − iv[i+L]|) / mean(iv)，L ∈ {1..min(4, k−1)}
     * r      = max(0, 1 − min rel(L))
     * </pre>
     *
     * <p>完美时钟（任意多脉冲复合）→ 1；抖动 → 部分损失；乱按 → 0。
     * v1 局限（文档 §3.8 注）：只看时间不看幅度，占空比渐变暂不可见。</p>
     */
    private void recomputeRegularity() {
        int k = risingTicks.size() - 1;                       // 间隔数
        if (k < 2) {
            regularity = 0;
            return;
        }
        double[] iv = new double[k];
        double mean = 0;
        Long prev = null;
        int i = 0;
        for (long t : risingTicks) {
            if (prev != null) {
                iv[i] = t - prev;
                mean += iv[i];
                i++;
            }
            prev = t;
        }
        mean /= k;
        if (mean <= 0) {
            regularity = 0;
            return;
        }

        double best = 0;
        int maxShift = Math.min(MAX_SHIFT, k - 1);
        for (int shift = 1; shift <= maxShift; shift++) {
            int cnt = k - shift;
            // 每个 shift 至少 2 个比较样本（L=1 例外），防小样本过拟合抬高 r
            if (cnt < 2 && shift > 1) continue;
            double mad = 0;
            for (int j = 0; j < cnt; j++) {
                mad += Math.abs(iv[j] - iv[j + shift]);
            }
            mad /= cnt;
            double rel = mad / mean;
            best = Math.max(best, Math.max(0.0, 1.0 - rel));
        }
        regularity = best;
    }
}
