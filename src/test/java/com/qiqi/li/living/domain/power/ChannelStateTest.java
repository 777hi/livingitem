package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChannelState} 相位域 / 偏移活性测试。
 *
 * <p>ChannelState 与 {@link PhaseEvent} 都是零 Minecraft 依赖的纯逻辑，
 * 可直接驱动 {@code onPhaseEvent} + {@code tickCleanup} 验证相数 n 的收敛行为，
 * 无需容器、Level 或真实铜块网络。</p>
 */
class ChannelStateTest {

    /** 驱动若干 tick：在每个 tick 上触发命中的偏移，随后执行清理。 */
    private static void run(ChannelState ch, int pref, int period,
            int[] offsets, long fromTick, long toTick) {
        for (long t = fromTick; t <= toTick; t++) {
            for (int off : offsets) {
                if (t % period == off) {
                    ch.onPhaseEvent(new PhaseEvent(off, period, off, 10, t), pref);
                }
            }
            ch.tickCleanup(t, pref);
        }
    }

    /**
     * 回归：拆掉部分振荡器后，n 必须回落。
     *
     * <p>布局：周期 20、4 路不同相位（偏移 0/5/10/15），偏好周期 16。</p>
     *
     * <p>修复前：域内偏移集合只增不减，只要还剩 1 路在跳，域就永不超时 →
     * 被拆掉 3 路的偏移永久残留 → n 永久停在 4 → 合因子虚高 → 长期白拿发电量。
     * 这是本测试锁定的核心行为。</p>
     */
    @Test
    @DisplayName("拆掉振荡器后，相数 n 回落到剩余路数（不再永久高估）")
    void removingOscillators_prunesStaleOffsets() {
        ChannelState ch = new ChannelState();
        int pref = 16;
        int period = 20;

        // 阶段一：4 路同周期不同相，跑 10 个周期
        run(ch, pref, period, new int[] {0, 5, 10, 15}, 0, 199);
        assertEquals(4, ch.bestN(pref), "前置条件：4 路振荡器应识别为 n=4");

        // 阶段二：只保留偏移 0，其余 3 路拆除，再跑 15 个周期
        run(ch, pref, period, new int[] {0}, 200, 499);

        assertEquals(1, ch.bestN(pref),
            "拆掉 3 路后 n 应回落到 1；修复前偏移只增不减，n 会永久停在 4");
    }

    /**
     * 稳态不得误剪：各偏移按周期正常心跳时，n 应长期稳定。
     *
     * <p>守护偏移超时不能太紧——偏移每 {@code period} tick 才重现一次，
     * 超时须按本域周期（而非偏好周期）计算。</p>
     */
    @Test
    @DisplayName("稳态下各偏移持续心跳，n 长期稳定不被误剪")
    void steadyState_offsetsSurviveAndNAccumulates() {
        ChannelState ch = new ChannelState();
        int pref = 16;
        int period = 20;

        // 跑 50 个周期
        run(ch, pref, period, new int[] {0, 5, 10, 15}, 0, 999);

        assertEquals(4, ch.bestN(pref), "稳态下 4 路偏移都应存活，n 保持 4");
        assertEquals(period, ch.bestPeriod(pref), "最佳域周期应为 20");
    }

    /**
     * 全部振荡器拆除后，域应被整体清理，n 归零。
     *
     * <p>与「只拆一部分」形成对照：整域静默走域级超时，部分拆除走偏移级剪枝。</p>
     */
    @Test
    @DisplayName("全部振荡器拆除后，域整体清理，n 归零")
    void allOscillatorsRemoved_domainCleared() {
        ChannelState ch = new ChannelState();
        int pref = 16;
        int period = 20;

        run(ch, pref, period, new int[] {0, 5, 10, 15}, 0, 199);
        assertEquals(4, ch.bestN(pref), "前置条件：应先识别到 n=4");

        // 全部移除：不再触发任何事件，只推进清理
        for (long t = 200; t <= 400; t++) {
            ch.tickCleanup(t, pref);
        }

        assertEquals(0, ch.bestN(pref), "全部拆除且静默足够久后，域应被清理，n 归零");
    }

    /**
     * 长周期 / 稀疏网络：域必须能跨心跳存活，n 才能累积。
     *
     * <p>布局：周期 130、仅 2 路（偏移 0 与 65），偏好周期 16。事件间隔 65 tick。</p>
     *
     * <p>修复前：域级超时只看偏好周期（{@code max(32, 16×2) = 32}），
     * 65 tick 的正常心跳间隙被误判为「静默」→ 整域在 t=33 被删 →
     * 每次重建 n 都从 1 重数，永远堆不到 2。这正是长周期深度振荡被抑制的原因。</p>
     *
     * <p>注意：这与「n=7 那种密集网络」无关——事件够密时域本就能跨心跳存活，
     * 涌现是真的；此测试守护的是另一种搭法（慢速、稀疏）。</p>
     */
    @Test
    @DisplayName("长周期稀疏网络：域跨心跳存活，n 能累积到 2")
    void longPeriodSparseNetwork_domainSurvivesBetweenBeats() {
        ChannelState ch = new ChannelState();
        int pref = 16;
        int period = 130;

        // 2 路，间隔 65 tick；跑 8 个周期
        run(ch, pref, period, new int[] {0, 65}, 0, period * 8L);

        assertEquals(2, ch.bestN(pref),
            "P=130 的 2 路稀疏网络，n 应累积到 2；修复前域在心跳间隙被误删，n 永远为 1");
    }

    /**
     * 相数 n 影响合因子：路数越多，合因子越高（剪枝确实会反映到产出上）。
     *
     * <p>确认上面的剪枝不是「只改了 n 的显示」，而是真的作用到 bestFactor。</p>
     */
    @Test
    @DisplayName("剪枝后合因子随之下降，不只是 n 的显示变化")
    void pruning_reducesCombinedFactor() {
        ChannelState ch = new ChannelState();
        int pref = 16;
        int period = 20;

        run(ch, pref, period, new int[] {0, 5, 10, 15}, 0, 199);
        double factorFourVoices = ch.bestFactor(pref);

        run(ch, pref, period, new int[] {0}, 200, 499);
        double factorOneVoice = ch.bestFactor(pref);

        assertEquals(1, ch.bestN(pref), "前置条件：剪枝后 n 应已回落到 1");
        org.junit.jupiter.api.Assertions.assertTrue(
            factorOneVoice < factorFourVoices,
            "拆掉 3 路后合因子应明显下降：4 路=" + factorFourVoices
                + "，1 路=" + factorOneVoice);
    }

    /**
     * 相位顺序回归：给客户端的相位必须「offset 升序 + (offset, |Δ|) 同序配对」。
     *
     * <p>背景（v19.2 根因）：旧实现取 {@code deltaByOffset().values()}——那是 HashMap
     * 的值视图，迭代顺序是哈希序而非 offset 序，而且 <b>offset 本身被整个丢掉</b>，
     * 客户端拿不到相位位置。后果是相位圆盘无从画起，只能画装饰性正弦。</p>
     *
     * <p>这里选的 offset 集合在 HashMap 里的桶序刻意不等于数值序（桶序 20→37→5→61），
     * 所以「忘了排序」会立刻被这个用例抓住。</p>
     */
    @Test
    @DisplayName("phasesSorted：相位按 offset 升序，且 offset 与 |Δ| 同序配对")
    void phasesSorted_returnsOffsetsAscendingAndPaired() {
        ChannelState ch = new ChannelState();
        int pref = 16;
        int period = 64;
        // 插入顺序与桶序都刻意打乱；数值序为 5 → 20 → 37 → 61
        int[] offsets = {37, 5, 61, 20};
        for (int off : offsets) {
            // delta 取 offset 本身，便于校验 (offset, |Δ|) 配对不错位
            ch.onPhaseEvent(new PhaseEvent(off, period, off, off, 0), pref);
        }

        var domain = ch.domains().get(period);
        org.junit.jupiter.api.Assertions.assertNotNull(domain, "前置条件：应存在 P=64 的域");

        List<Integer> gotOffsets = new ArrayList<>();
        for (var p : domain.phasesSorted()) {
            gotOffsets.add(p.getKey());
            assertEquals(p.getKey(), p.getValue(),
                "offset 与 |Δ| 必须同序配对（本次注入 delta = offset）");
        }
        assertEquals(List.of(5, 20, 37, 61), gotOffsets,
            "相位必须按 offset 升序；直接遍历 HashMap 会得到桶序 20,37,5,61");
    }

    /**
     * 最小相位间隔：相位圆盘在 n 很大时辐条会挤在一起，这个读数是它的数字兜底，
     * 同时量化「同相风险」——值越小说明有两路越接近合并成一路（n 会塌缩）。
     */
    @Test
    @DisplayName("minPhaseGap：取相邻相位最小间距，含跨周期回绕")
    void snapshot_minPhaseGap_wrapsAround() {
        var uniform = new LivingWaxedGeneratorData.DomainSnapshot(
            8, 4, 16, 12.0, List.of(0, 2, 4, 6), List.of(16, 9, 4, 9));
        assertEquals(2, uniform.minPhaseGap(), "P=8 均匀 4 路，最小间隔应为 2t");

        var sparse = new LivingWaxedGeneratorData.DomainSnapshot(
            10, 2, 9, 6.0, List.of(0, 7), List.of(9, 4));
        assertEquals(3, sparse.minPhaseGap(),
            "P=10 两路 φ=0 与 φ=7：相邻差 7，跨周期回绕 0+10-7=3，应取小者 3");
    }

    /**
     * 序列化往返：新增的 {@code offsets} 必须真的进了 codec。
     *
     * <p>这类 bug 非常安静——忘记把字段加进 {@code RecordCodecBuilder} /
     * {@code StreamCodec.composite} 都不会编译报错，只会在往返之后静默变成空列表，
     * 客户端因此拿不到相位、圆盘整块画不出来。这里守住 codec，
     * {@code DomainSnapshot.STREAM_CODEC} 共用同一份字段清单，一并被覆盖。</p>
     */
    @Test
    @DisplayName("DomainSnapshot 序列化往返：offsets / deltas 都保留且同序")
    void snapshot_codecRoundTrip_preservesPhases() {
        var original = new LivingWaxedGeneratorData.DomainSnapshot(
            8, 4, 16, 12.0, List.of(0, 2, 4, 6), List.of(16, 9, 4, 9));

        com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var encoded = LivingWaxedGeneratorData.DomainSnapshot.CODEC.encodeStart(ops, original).getOrThrow();
        var decoded = LivingWaxedGeneratorData.DomainSnapshot.CODEC.parse(ops, encoded).getOrThrow();

        assertEquals(original, decoded, "codec 往返应产生等值快照");
        assertEquals(List.of(0, 2, 4, 6), decoded.offsets(),
            "offsets 必须随 codec 往返保留，否则客户端拿不到相位位置");
        assertEquals(List.of(16, 9, 4, 9), decoded.deltas(),
            "deltas 必须与 offsets 同序保留，错位会让圆盘把幅度安错相位");
        assertEquals(2, decoded.minPhaseGap(), "往返后最小相位间隔应仍可算出");
    }

    /**
     * 最佳域定位：圆盘画的是最佳域的相位，顶层 {@code detectedPeriod} / {@code phaseCount}
     * 也来自最佳域——两者必须同域，否则圆盘与数字读数会各说各话。
     *
     * <p>这里故意把最佳域（P=20）放在列表第二位，所以「取首项」这种偷懒实现会立刻失败。
     * 唯一性由 {@code ChannelState} 的 {@code period → PhaseDomain} 映射保证。</p>
     */
    @Test
    @DisplayName("bestDomain：按 period 唯一定位最佳域，无信号时返回 null")
    void telemetry_bestDomain_matchesDetectedPeriod() {
        var d8 = new LivingWaxedGeneratorData.DomainSnapshot(
            8, 4, 16, 12.0, List.of(0, 2, 4, 6), List.of(16, 9, 4, 9));
        var d20 = new LivingWaxedGeneratorData.DomainSnapshot(
            20, 2, 9, 6.0, List.of(0, 10), List.of(9, 4));

        var t = new LivingWaxedGeneratorData(
            20, 2, 250, 9, 6000, 0, 100, 100, List.of(d8, d20), 1.0, 0.0, 1,
            List.of(0L, 0L, 0L, 0L));

        assertSame(d20, t.bestDomain(),
            "detectedPeriod=20 应命中 P=20 的域，而不是列表首项 P=8");
        assertEquals(List.of(0, 10), t.bestDomain().offsets(),
            "命中最佳域后，圆盘所需的相位偏移应可取到");

        var noSignal = new LivingWaxedGeneratorData(
            0, 0, 0, 0, 0, 0, 0, 0, List.of(d8), 1.0, 0.0, 0,
            List.of(0L, 0L, 0L, 0L));
        assertNull(noSignal.bestDomain(), "无信号（detectedPeriod=0）时应返回 null");
    }

    @Test
    @DisplayName("minPhaseGap：相位少于 2 路时返回 -1")
    void snapshot_minPhaseGap_belowTwoVoices() {
        var one = new LivingWaxedGeneratorData.DomainSnapshot(
            8, 1, 16, 4.0, List.of(3), List.of(16));
        assertEquals(-1, one.minPhaseGap(), "单路相位无间隔可言，应返回 -1");

        var none = new LivingWaxedGeneratorData.DomainSnapshot(
            0, 0, 0, 0.0, List.of(), List.of());
        assertEquals(-1, none.minPhaseGap(), "无相位应返回 -1");
    }
}
