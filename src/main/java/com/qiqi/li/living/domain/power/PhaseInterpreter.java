package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.container.ContainerContext;

/**
 * 相位解读三元件（v19，§3.8）—— 从真实边信号派生「相位」，写进派生相位注册表。
 *
 * <p><b>2026-10-09 power 收口 步骤 4 第 4 刀</b>：从 {@code LivingWaxedCopperFunction}
 * <b>纯搬迁</b>（零逻辑改动）。可以独立成类的理由：它只「读」边跟踪器
 * （{@link SignalTracker}）与注册表、只「写」派生注册表草稿，<b>与 tick 编排无关</b> ——
 * 不碰通道事件注入，也不改任何槽位物品状态。</p>
 *
 * <p><b>三元件</b>：雕文 = 移相器（φ+1）/ 切制 = 裂相器（读下降沿）/ 格栅 = 加法器（Σφ）。
 * 完整设计与「链而非环」的结构论证见 {@code living-power-tech.md} §3.8。</p>
 *
 * <p>⚠️ {@code getCoilForm} <b>留在</b> {@code LivingWaxedCopperFunction}（被
 * {@code CoilGroupingTest} 直接引用，且它映射 power 域的 {@code FORM_*}，
 * 迁到 L1 会造反向依赖 —— 见 {@code WaxedCopperFamily} javadoc）。本类按包内可见性调用它。</p>
 */
final class PhaseInterpreter {

    private PhaseInterpreter() {}

    // ── 相位解读 pass（v19 相位解读三元件）──

    /**
     * 三形态相位元件各解读输入信号，派生相位写注册表。
     *
     * <p>解读是「驻波」语义：输入 = 稳定的周期波形（边跟踪器的锁相状态 /
     * 邻居注册表的驻波条目），派生输出同样是驻波——每 tick 重算自己的条目；
     * 注入侧在 {@code now ≡ offset (mod P)} 的 tick 视为上升沿入账。</p>
     *
     * <p><b>组合与防环（结构性，无检测代码）</b>：元件间组合只经移相链
     * （雕文读输入方向邻居的注册表）；加法器只读自己的真实边（不读注册表）、
     * 裂相器无外部输入。因此依赖图是「链」而非「环」——移相环的每个成员的
     * 输入边都是蜡-蜡死边（无种子），注册表永远为空，环自熄；不存在
     * 「互读导致偏移每 tick 自增跑满」的通路。
     * ⚠️ 移相链的组合入口（雕文读邻居注册表）已于 2026-09-08 暂时关闭
     * （满相可堆叠涌现，增益超模，见 interpretShifter）——组合语义暂不生效，
     * 但「链而非环」的结构论证仍然成立，重新启用时无需重审防环。</p>
     *
     * <p><b>活性</b>：输入源停跳超过 {@link PowerMath#aliveWindow} 后，
     * 对应解读停止、驻波经 {@link ContainerPowerData#pruneRegistry} 修剪——死源不发电。</p>
     *
     * <p><b>两阶段提交</b>：先全部算入草稿、再统一写回——解读过程中读取的
     * 邻居注册表一律是上一 tick 的状态，与槽位处理顺序无关。</p>
     */
    static void phaseInterpretation(Map<Integer, GeneratorState> active,
            ContainerContext ctx, int size, int width, ContainerPowerData powerData, long now) {
        powerData.pruneRegistry(now);

        // 首拍无沿宽限：warmup 期间边跟踪器的锁相尚未确立（首个假沿污染周期估计），
        // 解读出的派生相位不可信——跳过本 pass，注册表保持空，warmup 结束后自然重建。
        if (powerData.inWarmup()) return;

        Map<Integer, List<DerivedPhase>> draft = new HashMap<>();
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            ItemStack stack = ctx.getItem(slot);
            int cf = LivingWaxedCopperFunction.getCoilForm(stack.getItem());
            switch (cf) {
                case LivingWaxedGeneratorData.FORM_CHISELED ->
                    interpretShifter(slot, stack, ctx, size, width, powerData, now, draft);
                case LivingWaxedGeneratorData.FORM_CUT ->
                    interpretSplitter(slot, powerData, now, draft);
                case LivingWaxedGeneratorData.FORM_GRATE ->
                    interpretAdder(slot, powerData, now, draft);
                default -> { } // 基座铜块 / 铜灯：只会「读」不会「造」
            }
        }
        for (var e : draft.entrySet()) {
            powerData.setRegistry(e.getKey(), e.getValue());
        }
    }

    /**
     * 雕文 = 移相器：读信号的「位置」。
     *
     * <p>解读规则：对输入方向上的每一路锁相波形 (P, φ, δ)（真实边 + 输入方向
     * 邻居的注册表驻波），派生 (P, (φ+1) mod P, δ)——驻波整体延迟 1 tick。
     * k 台首尾相连（后者的输入方向指向前者）= 任意偏移延迟线，解锁奇数偏移制造
     * （中继器延迟全是偶数 tick）。环自熄：环上成员的输入边都是蜡-蜡死边，无种子。</p>
     *
     * <p><b>暂时关闭链式组合（2026-09-08）</b>：邻居注册表读取已停用（见方法体
     * 内注释）——移相链让任意频率信号堆出满相（n = P），绕过「真多相靠布局」的
     * 核心设计，增益超模。单级移相（真实边 φ+1）保留，与切制/格栅同口径。</p>
     */
    private static void interpretShifter(int slot, ItemStack stack, ContainerContext ctx,
            int size, int width, ContainerPowerData powerData, long now,
            Map<Integer, List<DerivedPhase>> draft) {
        var data = LivingWaxedChiseledData.of(stack);
        int inEdge = CopperNetworkTopology.pos2dToEdgeDir(data.inputDir());
        List<DerivedPhase> out = new ArrayList<>();

        // 输入一：输入方向边上的真实波形（锁相状态，活性窗口内）
        SignalTracker t = powerData.getEdgeTracker(CopperNetworkTopology.edgeKey(slot, inEdge));
        if (t != null && t.period() > 0
                && now - t.lastRisingTick() <= PowerMath.aliveWindow(t.period())) {
            out.add(new DerivedPhase(t.period(), Math.floorMod(t.offset() + 1, t.period()),
                t.lastDelta(), DerivedPhase.KIND_SHIFT, now));
        }

        // 输入二：输入方向邻居的注册表驻波（移相链的组合入口）
        // 【暂时关闭 2026-09-08】移相链（雕文首尾相连）允许任意频率信号堆出任意偏移，
        // 绕过「真多相要靠布局与时序」的核心设计——单台雕文只要有足够长的链就能凑满相
        // （n = P），增益封顶只剩材料成本。关闭后雕文与切制/格栅同口径：只读真实边信号，
        // 单级移相（φ+1）保留、链式组合断开。逻辑保留备将来重新设计增益约束后启用。
        // int neighbor = ContainerContext.resolveNeighbor(slot, inEdge, size, width);
        // if (neighbor >= 0) {
        //     for (DerivedPhase dp : powerData.getRegistry(neighbor)) {
        //         out.add(new DerivedPhase(dp.period(), Math.floorMod(dp.offset() + 1, dp.period()),
        //             dp.delta(), DerivedPhase.KIND_SHIFT, now));
        //     }
        // }

        if (!out.isEmpty()) draft.put(slot, out);
    }

    /**
     * 切制 = 裂相器：读信号的「另一半」。
     *
     * <p>解读规则：每条边的下降沿波形（独立跟踪器）直接登记为派生相位——
     * 一个方波贡献 2 个反相相位（上升沿 φ 由全网真实采样、下降沿 φ_f 由裂相器
     * 补齐）。非对称波形的 φ_f ≠ φ + P/2（涌现）。P=2 时钟 + 1 台切制 → n=2 满相。
     * 裂相不是延迟：偏移取下降沿自身位置，从下一个周期起注入。</p>
     */
    private static void interpretSplitter(int slot, ContainerPowerData powerData, long now,
            Map<Integer, List<DerivedPhase>> draft) {
        List<DerivedPhase> out = new ArrayList<>();
        for (int dir = 0; dir < RedstoneSensor.DIRECTIONS; dir++) {
            SignalTracker t = powerData.getEdgeTracker(CopperNetworkTopology.FALLING_BIT | CopperNetworkTopology.edgeKey(slot, dir));
            if (t != null && t.period() > 0
                    && now - t.lastRisingTick() <= PowerMath.aliveWindow(t.period())) {
                out.add(new DerivedPhase(t.period(), t.offset(),
                    t.lastDelta(), DerivedPhase.KIND_SPLIT, now));
            }
        }
        if (!out.isEmpty()) draft.put(slot, out);
    }

    /**
     * 格栅 = 相位加法器：读信号间的「关系」。
     *
     * <p>解读规则：汇集 4 条边的真实锁相波形，按周期分桶；对含 ≥2 路的桶派生
     * (P, Σφᵢ mod P, min δᵢ)——信号层「多路幅度求和」的电力层镜像（相位求和）。
     * 去重诚实：和已存在于域内则无增益；同相位双输入 → 2φ「翻倍」可算。</p>
     *
     * <p>v1 不读注册表（组合经移相链实现）：加法器若互读邻居驻波，两只对摆且
     * 各有真实输入时会互相把对方的和吸进自己的和，偏移沿加法子群逐 tick 自增
     * 跑满——结构上掐断这条唯一的成环通路。</p>
     */
    private static void interpretAdder(int slot, ContainerPowerData powerData, long now,
            Map<Integer, List<DerivedPhase>> draft) {
        Map<Integer, List<int[]>> byPeriod = new HashMap<>(); // period → [offset, delta]
        for (int dir = 0; dir < RedstoneSensor.DIRECTIONS; dir++) {
            SignalTracker t = powerData.getEdgeTracker(CopperNetworkTopology.edgeKey(slot, dir));
            if (t == null || t.period() <= 0) continue;
            if (now - t.lastRisingTick() > PowerMath.aliveWindow(t.period())) continue;
            byPeriod.computeIfAbsent(t.period(), k -> new ArrayList<>())
                .add(new int[] {t.offset(), t.lastDelta()});
        }
        List<DerivedPhase> out = new ArrayList<>();
        for (var e : byPeriod.entrySet()) {
            List<int[]> phases = e.getValue();
            if (phases.size() < 2) continue; // 单路无可加
            int period = e.getKey();
            int sum = 0;
            int minDelta = Integer.MAX_VALUE;
            for (int[] p : phases) {
                sum = Math.floorMod(sum + p[0], period);
                minDelta = Math.min(minDelta, p[1]);
            }
            out.add(new DerivedPhase(period, sum, minDelta, DerivedPhase.KIND_ADD, now));
        }
        if (!out.isEmpty()) draft.put(slot, out);
    }

    /** 派生驻波的事件源 id（仅信息用途：域按周期分桶、偏移去重） */
    static int derivedSourceId(int slot, DerivedPhase dp) {
        return (slot << 3) | dp.kind();
    }
}
