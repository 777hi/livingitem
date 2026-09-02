package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
