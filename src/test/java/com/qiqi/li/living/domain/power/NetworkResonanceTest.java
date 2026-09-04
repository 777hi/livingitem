package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网络级共振测试（§3.7）——不同锈蚟级之间的「和声」。
 *
 * <p>公式：{@code s = GM/AM}，{@code R = 1 + (N−1)×s}，{@code 增益 = R^exp}。
 * 锈级共振单位是<strong>锈蚟级</strong>（不是 BFS 连通块），同锈蚟级的多个连通块
 * 出力相加——这是 {@code R ≤ 4} 的结构性前提。</p>
 *
 * <p>本类的核心是<strong>安全性</strong>：共振只读「共振前」的基础出力，
 * 单遍前馈，绝不回代。{@link #noFeedbackGainStaysBounded} 为此红线把关。</p>
 */
class NetworkResonanceTest {

    private static final double EPS = 1e-9;
    private static final double LOOSE = 1e-3;

    // ── 平衡度 s ──

    @Test
    @DisplayName("平衡度：全部相等为 1；元素数 ≤ 1 亦为 1")
    void balance_equalIsOne() {
        assertEquals(1.0, PowerMath.balanceFactor(new double[]{100, 100, 100, 100}), LOOSE);
        assertEquals(1.0, PowerMath.balanceFactor(new double[]{7}), EPS);
        assertEquals(1.0, PowerMath.balanceFactor(new double[]{0, 0, 250, 0}), EPS);
    }

    @Test
    @DisplayName("平衡度：[100,100,100,1] ≈ 0.4202；[100,1,1,1] ≈ 0.1228")
    void balance_knownValues() {
        assertEquals(0.42024, PowerMath.balanceFactor(new double[]{100, 100, 100, 1}), 1e-5);
        assertEquals(0.12281, PowerMath.balanceFactor(new double[]{100, 1, 1, 1}), 1e-5);
        assertEquals(0.961024, PowerMath.balanceFactor(new double[]{100, 100, 100, 50}), 1e-5);
    }

    @Test
    @DisplayName("平衡度：尺度无关（1000/1000 与 1/1 同样共振）")
    void balance_scaleInvariant() {
        assertEquals(PowerMath.balanceFactor(new double[]{1000, 1000}),
            PowerMath.balanceFactor(new double[]{1, 1}), LOOSE);
        assertEquals(PowerMath.balanceFactor(new double[]{900, 100}),
            PowerMath.balanceFactor(new double[]{9, 1}), LOOSE);
    }

    // ── 共振倍率 R ──

    @Test
    @DisplayName("孤网（单锈级）：R = 1，无共振")
    void resonance_singleVoice_isOne() {
        assertEquals(1.0, PowerMath.resonanceFactor(new double[]{500}), EPS);
        assertEquals(1.0, PowerMath.resonanceFactor(new double[]{0, 0, 500, 0}), EPS);
    }

    @Test
    @DisplayName("等大 N 网：R 恰好 = N（2/3/4）")
    void resonance_equalVoices_equalsN() {
        assertEquals(2.0, PowerMath.resonanceFactor(new double[]{100, 100}), LOOSE);
        assertEquals(3.0, PowerMath.resonanceFactor(new double[]{100, 100, 100}), LOOSE);
        assertEquals(4.0, PowerMath.resonanceFactor(new double[]{100, 100, 100, 100}), LOOSE);
    }

    @Test
    @DisplayName("严重失衡：R 退化为 1（共振永不扣发电量）")
    void resonance_dominantVoice_degradesToOne() {
        double r = PowerMath.resonanceFactor(new double[]{100000, 1, 1, 1});
        assertTrue(r >= 1.0, "R 不得小于 1");
        assertTrue(r < 1.05, "独大时 R 应趋近 1，实际 " + r);
    }

    @Test
    @DisplayName("空 / 全零输入：R = 1")
    void resonance_emptyOrAllZero_isOne() {
        assertEquals(1.0, PowerMath.resonanceFactor(new double[]{0, 0, 0, 0}), EPS);
        assertEquals(1.0, PowerMath.resonanceFactor(new double[0]), EPS);
        assertEquals(1.0, PowerMath.resonanceFactor(null), EPS);
    }

    @Test
    @DisplayName("零值不计入锈级数 N：2 级发电 + 2 级空 → N = 2")
    void resonance_zerosExcludedFromVoiceCount() {
        // 与 [100,100] 完全等价（两个空锈蚟级不得拖低平衡度）
        assertEquals(PowerMath.resonanceFactor(new double[]{100, 100}),
            PowerMath.resonanceFactor(new double[]{100, 100, 0, 0}), LOOSE);
    }

    @Test
    @DisplayName("上界：任意输入恒有 R ≤ 4（锈蚟级数结构性封顶）")
    void resonance_upperBound_isFour() {
        double[][] cases = {
            {100, 100, 100, 100},
            {1, 1, 1, 1},
            {1e9, 1e9, 1e9, 1e9},
            {100, 100, 100, 99.999},
            {5, 7, 6, 5.5},
        };
        for (double[] c : cases) {
            double r = PowerMath.resonanceFactor(c);
            assertTrue(r <= PowerMath.OXIDATION_LEVELS + EPS,
                "R 应 ≤ 4，实际 " + r);
            assertTrue(r >= 1.0 - EPS, "R 应 ≥ 1，实际 " + r);
        }
    }

    @Test
    @DisplayName("锈级数超过锈蚟级数时被截断（防御：拆簇刷 N）")
    void resonance_voiceCountCappedAtOxidationLevels() {
        // 防御性输入：即便传入 8 个锈级，N 也被截断到 4
        double r = PowerMath.resonanceFactor(new double[]{10, 10, 10, 10, 10, 10, 10, 10});
        assertTrue(r <= PowerMath.OXIDATION_LEVELS + EPS, "R 应被截断到 ≤ 4，实际 " + r);
    }

    // ── 共振增益 R^exp ──

    @Test
    @DisplayName("增益 = R²：等大 4 网 → 16，等大 2 网 → 4")
    void gain_squaredByDefault() {
        assertEquals(2.0, PowerMath.RESONANCE_EXPONENT, EPS);
        assertEquals(16.0, PowerMath.resonanceGain(new double[]{100, 100, 100, 100}), LOOSE);
        assertEquals(4.0, PowerMath.resonanceGain(new double[]{100, 100}), LOOSE);
        assertEquals(9.0, PowerMath.resonanceGain(new double[]{100, 100, 100}), LOOSE);
        assertEquals(1.0, PowerMath.resonanceGain(new double[]{100}), EPS);
    }

    @Test
    @DisplayName("「凑数」不划算：3 级完美 > 3 级 + 1 个弱级")
    void gain_addingWeakVoiceLosesPower() {
        long basePerfect = 300;
        double gainPerfect = PowerMath.resonanceGain(new double[]{100, 100, 100});
        double totalPerfect = basePerfect * gainPerfect;

        long baseWeak = 301;
        double gainWeak = PowerMath.resonanceGain(new double[]{100, 100, 100, 1});
        double totalWeak = baseWeak * gainWeak;

        assertTrue(totalWeak < totalPerfect,
            "硬塞弱锈级应拉低总出力：" + totalWeak + " 应 < " + totalPerfect);
        // 参考值：2700 vs ~1538
        assertEquals(2700.0, totalPerfect, 1.0);
        assertEquals(1538.0, totalWeak, 2.0);
    }

    // ── 安全性红线：禁回代 ──

    @Test
    @DisplayName("回代安全：连续 500 tick 恒定基础出力，增益收敛到 16 而不发散")
    void noFeedbackGainStaysBounded() {
        ContainerPowerData data = new ContainerPowerData();
        long[] base = {100, 100, 100, 100};   // 每 tick 各锈蚟级基础出力（共振前）
        long baseSum = 400;
        double maxGain = 0.0;

        for (int i = 0; i < 500; i++) {
            // 关键：只喂**基础**出力。绝不能喂乘过增益的值，否则形成回代环。
            data.updateOxidationEma(base);
            double gain = data.resonanceGain();
            long generated = Math.round(baseSum * gain);   // 本 tick 实际发电

            assertTrue(gain >= 1.0 - EPS, "增益不得小于 1");
            assertTrue(gain <= 16.0 + LOOSE, "增益不得超过 16，实际 " + gain);
            assertTrue(generated <= baseSum * 16 + 1, "单 tick 发电量必须有界");
            maxGain = Math.max(maxGain, gain);
        }
        assertEquals(16.0, maxGain, 0.05, "满共振应收敛到 16 而非持续增长");
        assertEquals(4, data.activeOxidationLevels());
    }

    @Test
    @DisplayName("回代安全：增益不回灌 EMA —— EMA 恒等于基础值本身")
    void noFeedbackEmaTracksBaseOnly() {
        ContainerPowerData data = new ContainerPowerData();
        long[] base = {40, 40, 0, 0};
        for (int i = 0; i < 300; i++) {
            data.updateOxidationEma(base);
            // 每 tick 都按增益放大发电量，但这个值不写回 EMA
            double gain = data.resonanceGain();
            assertTrue(gain > 1.0);
        }
        // EMA 应收敛到基础值 40/40，而不是被增益放大后的值
        double[] ema = data.getEmaPowerByOxidation();
        assertEquals(40.0, ema[0], 0.5);
        assertEquals(40.0, ema[1], 0.5);
        assertEquals(0.0, ema[2], 0.5);
        assertEquals(0.0, ema[3], 0.5);
        assertEquals(2, data.activeOxidationLevels());
    }

    @Test
    @DisplayName("EMA 衰减：停止发电的锈蚟级会淡出，不再拖低平衡度")
    void emaDecaysWhenVoiceStops() {
        ContainerPowerData data = new ContainerPowerData();
        long[] four = {100, 100, 100, 100};
        for (int i = 0; i < 200; i++) data.updateOxidationEma(four);
        assertEquals(4, data.activeOxidationLevels());

        // 第 4 级停止发电
        long[] three = {100, 100, 100, 0};
        for (int i = 0; i < 400; i++) data.updateOxidationEma(three);
        double[] ema = data.getEmaPowerByOxidation();
        assertTrue(ema[3] < 1.0, "停发的锈蚟级 EMA 应衰减到可忽略，实际 " + ema[3]);
        assertEquals(3, data.activeOxidationLevels());
        assertEquals(3.0, data.resonanceFactor(), 0.05);
    }

    // ── 场景对照（与《红电系统》设计讨论中的演算互为验证）──

    @Test
    @DisplayName("场景对照：总量 400 的几种分配（单网 400 / 2 网 1600 / 4 网 6400）")
    void scenario_totalFourHundredSplits() {
        assertEquals(400.0, total(new double[]{400}), 1.0);
        assertEquals(1600.0, total(new double[]{200, 200}), 2.0);
        assertEquals(3600.0, total(new double[]{133, 133, 134}), 5.0);
        assertEquals(6400.0, total(new double[]{100, 100, 100, 100}), 5.0);
        // 失衡分配
        assertEquals(1550.0, total(new double[]{250, 150}), 5.0);
        assertEquals(5278.0, total(new double[]{100, 100, 100, 50}), 5.0);
    }

    /** 容器本 tick 总发电 = Σ base × R^exp */
    private static double total(double[] powerByOxidation) {
        double sum = 0.0;
        for (double v : powerByOxidation) sum += v;
        return sum * PowerMath.resonanceGain(powerByOxidation);
    }

    // ── tooltip 数据落库（buildTelemetry 把容器级共振推到每件发电机组件）──

    @Test
    @DisplayName("buildTelemetry：4 锈级满共振 → 增益 16、平衡度 1、锈级 4、各锈级出力≈2700")
    void telemetry_fullResonance() {
        ContainerPowerData pd = new ContainerPowerData();
        for (int i = 0; i < 300; i++) pd.updateOxidationEma(new long[]{2700, 2700, 2700, 2700});
        LivingWaxedGeneratorData t = LivingWaxedCopperFunction.buildTelemetry(
            new GeneratorState(), 1, LivingWaxedGeneratorData.FORM_BLOCK, 0, pd);
        assertEquals(4, t.activeLevels());
        assertEquals(16.0, t.resonanceGain(), LOOSE);
        assertEquals(1.0, t.resonanceBalance(), LOOSE);
        assertEquals(4, t.levelPower().size());
        for (long v : t.levelPower()) assertEquals(2700, v);
    }

    @Test
    @DisplayName("buildTelemetry：单锈级 → 增益 1、锈级 1、无共振加成")
    void telemetry_singleVoice() {
        ContainerPowerData pd = new ContainerPowerData();
        for (int i = 0; i < 300; i++) pd.updateOxidationEma(new long[]{2700, 0, 0, 0});
        LivingWaxedGeneratorData t = LivingWaxedCopperFunction.buildTelemetry(
            new GeneratorState(), 1, LivingWaxedGeneratorData.FORM_BLOCK, 0, pd);
        assertEquals(1, t.activeLevels());
        assertEquals(1.0, t.resonanceGain(), EPS);
        // levelPower 始终长度 = 锈蚟级数（4），空锈级为 0
        assertEquals(4, t.levelPower().size());
        assertEquals(2700, t.levelPower().get(0));
        assertEquals(0, t.levelPower().get(1));
        assertEquals(0, t.levelPower().get(2));
        assertEquals(0, t.levelPower().get(3));
    }
}
