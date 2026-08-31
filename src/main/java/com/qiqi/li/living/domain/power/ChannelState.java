package com.qiqi.li.living.domain.power;

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
 * 合因子 = (log₂|Δ| × n)^(1 + 解锁度)，解锁度 = 调谐效率 × (n / 偏好周期)。</p>
 */
public class ChannelState {

    private final List<PathState> paths = new ArrayList<>();

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

    /**
     * 该路当前一次跳变适用的合因子 (log₂|Δ| × n)^(1+u)。
     * 不可用路返回 0（杂讯不产出）。
     */
    public double factorFor(int pathIndex, int preferredPeriod, int delta) {
        PathState path = paths.get(pathIndex);
        if (!path.hasUsablePhase()) return 0;
        int n = Math.max(1, path.domainN());
        double eff = PowerMath.tuningEfficiency(
            Math.abs(path.periodTicks() - preferredPeriod), preferredPeriod);
        double unlock = n <= 0 ? 0 : Math.min(1.0, eff * n / preferredPeriod);
        return PowerMath.combinedFactor(delta, n, unlock);
    }

    /** 上升沿：更新相位域重算（O(常数)） */
    private void onRising(long tick) {
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
}