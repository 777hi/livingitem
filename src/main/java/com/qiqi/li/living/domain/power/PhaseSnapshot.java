package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.DataResult;

import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.SignalTracker;

/**
 * 相位快照 —— 容器红电账本的跨会话持久化形态（2026-09-09 相位快照落盘）。
 *
 * <p><b>动机</b>：相位账本（周期估计 / 偏移 / 锚定 tick）是纯内存缓存，账本死亡
 * （LRU 120s 回收、退出重进、跨存档搬运）后所有振荡器从零重锁——多路相对相位
 * 被「重载时刻」重新洗牌，玩家调好的满相布局（n=P）不再适配。快照把退出前
 * 已锁定的锁相状态冻结，重进后以它为初值无缝续接（肉鸽感只留给真正的重新搭建）。</p>
 *
 * <p><b>时间轴平移</b>：{@code tickCounter} 是容器本地 tick（不与世界 tick 对齐），
 * 会话间不连续。快照存 (P, φ, 距上次上升沿的 tick 数 d)：φ 是周期内偏移、
 * 回填时 {@code lastRisingTick = 新 tickCounter − d}——本地时间轴平移下
 * φ 天然不变（P 整除性质），无需任何世界 tick 换算。回填后首个真实跳变会
 * 覆盖校准（漂移 ≤1 tick 自愈，与 EMA 收敛同款）。</p>
 *
 * <p><b>只存锁相结果，不存派生量</b>：EMA / 共振窗口 / 派生相位注册表都是从
 * 真实跳变推导的快变化量，落盘只会引入「磁盘账本说谎」面——玩家改造线路后
 * 旧快照 2~3 个周期内被真实跳变覆盖自愈，这正是只存 (P, φ, d) 的理由：
 * 它们是「慢变量」（振荡器物理决定），而快变量让它自然重学。</p>
 *
 * @param tickCounter 快照时的容器本地 tick 计数（回填平移基准，可不存精确值）
 * @param edges       边跟踪器快照（edgeKey → 锁相状态）
 */
public record PhaseSnapshot(
    long tickCounter,
    List<EdgeEntry> edges
) {

    /**
     * 单条边跟踪器的锁相快照。
     *
     * @param edgeKey     边标识（含 FALLING_BIT 命名空间的下降沿跟踪器）
     * @param periodTicks 周期估计（tick；&lt;2 = 未锁相，回填时跳过）
     * @param offset      周期内偏移 φ（0 ~ period-1）
     * @param sinceRise   距上次上升沿经过的 tick 数（回填平移用）
     * @param delta       上次跳变幅度 |Δ|
     */
    public record EdgeEntry(
        long edgeKey,
        int periodTicks,
        int offset,
        int sinceRise,
        int delta
    ) {
        public static final Codec<EdgeEntry> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.LONG.fieldOf("key").forGetter(EdgeEntry::edgeKey),
                Codec.INT.fieldOf("period").forGetter(EdgeEntry::periodTicks),
                Codec.INT.fieldOf("offset").forGetter(EdgeEntry::offset),
                Codec.INT.fieldOf("since_rise").forGetter(EdgeEntry::sinceRise),
                Codec.INT.fieldOf("delta").forGetter(EdgeEntry::delta)
            ).apply(instance, EdgeEntry::new));
    }

    public static final PhaseSnapshot EMPTY = new PhaseSnapshot(0, List.of());

    public static final Codec<PhaseSnapshot> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.LONG.fieldOf("tick").forGetter(PhaseSnapshot::tickCounter),
            EdgeEntry.CODEC.listOf().fieldOf("edges").forGetter(PhaseSnapshot::edges)
        ).apply(instance, PhaseSnapshot::new));

    // ── 抓取与回填 ──

    /** 存活窗口内才算有效锁相（与 pruneRegistry 同口径）——死边不进快照 */
    private static boolean worthSaving(SignalTracker t, long now) {
        if (t == null) return false;
        int p = t.period();
        if (p < 2) return false;
        // 负 lastRisingTick 是回填平移的合法状态（restoreInto: base − sinceRise），必须放行——
        // 只拒「未来锚」（now 之前不可能发生）。误拒会让回填后的边在首个真实跳变前
        // 被逐 tick 快照降级掉，二次退出即丢相位（2026-09-09 修复）。
        if (t.lastRisingTick() > now) return false;
        return now - t.lastRisingTick() <= PowerMath.aliveWindow(p);
    }

    /**
     * 从运行中的容器电力账本抓取快照（容器每 tick 末写回 BE 时调用）。
     * 未锁相或已停跳的边不进快照——它们本来也撑不过存活窗口。
     *
     * @param now 相位时钟当前值（2026-09-11 换轴：世界 game time；
     *            与 tickContainerData 的 resolvePhaseClock 同源——快照必须
     *            存在与驱动同一坐标系，否则回填后 φ 会在首个真实跳变时重锚）
     */
    public static PhaseSnapshot capture(ContainerPowerData power, long now) {
        List<EdgeEntry> out = new ArrayList<>();
        power.forEachEdgeTracker((edgeKey, tracker) -> {
            if (!worthSaving(tracker, now)) return;
            out.add(new EdgeEntry(edgeKey, tracker.period(), tracker.offset(),
                (int) Math.min(Integer.MAX_VALUE, now - tracker.lastRisingTick()),
                tracker.lastDelta()));
        });
        return new PhaseSnapshot(now, out);
    }

    /**
     * 回填快照到新账本（容器首次 tick / 账本重建后调用）。
     *
     * <p><b>2026-09-11 换轴</b>：基准时钟由调用方传入（世界 game time）——与
     * 退出前 capture 存的是同一坐标系，回填 {@code lastRisingTick = now − sinceRise}
     * 后 φ 与快照一致；首个真实跳变 interval = (P−d)−(−d) = P 精确续接，φ 不再重锚。
     * 旧版 base = power.currentTick()（容器本地轴，重进归零）是「修了和没修一样」
     * 的根因：快照的 φ 是旧坐标系的值，回填到新坐标系，首跳立刻重新锚定。</p>
     *
     * <p>回填后账本<strong>保持 warmup</strong>：warmup 与快照互补——首拍假沿由
     * {@code hasEdgeHistory()} 根修整段拦下，warmup 剩余几 tick 只防「快照未覆盖
     * 的新边」被首几个跳变过早入账。</p>
     *
     * @param now 相位时钟当前值（与 capture 同源：世界 game time / 测试本地轴）
     * @return 实际回填的边数（快照为空时 0）
     */
    public int restoreInto(ContainerPowerData power, long now) {
        if (edges.isEmpty()) return 0;
        for (EdgeEntry e : edges) {
            if (e.periodTicks() < 2) continue;
            // φ 反推锚（2026-09-11 手算验证）：anchor = now − ((now − φ) mod P)——
            // 锚是「now 之前最近的 φ 同余点」：offset() 恒等于快照 φ，且下一个
            // 真实跳变（now + ((φ − now) mod P)）的 interval 恰为 P。
            // 旧版 base − sinceRise 在跨轴（重进 ΔW）下 φ 平移 (ΔW mod P)。
            // 注意方向：必须减「now 的余数」，减「传入锚的余数」会把锚推向过去
            // 更远的同余点（interval 变 2P）——第一次修正踩过的坑。
            long anchor = now - Math.floorMod(now - e.offset(), e.periodTicks());
            SignalTracker t = power.getOrCreateEdgeTracker(e.edgeKey());
            t.restoreLocked(e.periodTicks(), e.offset(), anchor, e.delta());
        }
        return edges.size();
    }
}
