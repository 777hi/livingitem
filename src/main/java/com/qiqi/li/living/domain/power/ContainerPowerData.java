package com.qiqi.li.living.domain.power;

import java.util.HashMap;
import java.util.Map;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.SignalTracker;

/**
 * 容器级红电数据 —— 与 {@code ContainerRedstoneData} 并列的电力层账本。
 *
 * <p>纯 Java 类，零 Minecraft 依赖（事件与数值全部由 glue 层喂入），
 * 可直接用 JUnit 驱动（见 {@code ContainerPowerDataTest}）。</p>
 *
 * <h3>记账模型（§3.6/§3.10 v18，事件驱动 + 锈级分账）</h3>
 * <ul>
 *   <li>跳变即能量事件：RE 在 glue 层直接按锈级累加进
 *       {@code baseReByOx[k]}（共振口径 + v18 分账），tick 末按增益分配入同色铜灯
 *       （无同色灯则弃——发电必须被消费或储存）；</li>
 *   <li>按锈级的 EMA 平滑出「该锈级当前功率」读数（RE/t），对外经 K 换算为 FE；
 *       共振只读 EMA（基础值），增益绝不回灌——禁回代铁律的落地点；</li>
 *   <li>**无容器池、无总账 EMA**（v18 拆除）：铜灯是唯一储存，容器只是铜灯的架子；
 *       电按锈级分账后容器级「总功率」没有消费方，读数口径 = 各锈级 EMA。</li>
 * </ul>
 */
public class ContainerPowerData {

    /** EMA 平滑系数（约 8 tick 记忆） */
    private static final double EMA_ALPHA = 0.125;

    /**
     * 共振 EMA 的归零阈值（浮点卫生常数，**非**平衡常数）。
     *
     * <p>EMA 是指数衰减，数学上永远趋近 0 而达不到 0。若不设截断，某个锈蚟级
     * 停止发电后其 EMA 会以一个极小但非零的值长期存在，被
     * {@link #activeOxidationLevels()} 永远算作活跃锈级，进而把平衡度 s 永久压在
     * 接近 0——拆除一个网会毁掉整个容器的共振且无法自愈。</p>
     *
     * <p>RE 是以 1 为最小单位的整数，因此 1e-6 远低于任何有意义的出力。
     * 典型量级（1000 RE）约 155 tick（≈8 秒）后归零。</p>
     */
    private static final double EMA_EPSILON = 1e-6;

    private final Map<Integer, GeneratorState> generators = new HashMap<>();

    /** 振荡器信号跟踪器：slot → SignalTracker（按槽位跟踪，用于网络传播） */
    private final Map<Integer, SignalTracker> signalTrackers = new HashMap<>();

    /** 铜块网络边信号跟踪器：edgeKey = (slot << 2) | dir → SignalTracker（跨 tick 持久） */
    private final Map<Long, SignalTracker> edgeTrackers = new HashMap<>();

    /**
     * 各锈蚟级的**基础**出力 EMA（共振 + 锈级功率读数共用，见 living-power-tech.md §3.7）。
     *
     * <p>⚠️ 只跟踪共振前的基础出力。若喂入乘过共振增益的值，会形成
     * {@code 增益↑ → EMA↑ → s 变 → 增益↑} 的回代环，必须杜绝。
     * 共振是容器级账本行为，因此这里按锈蚟级（而非按 BFS 连通块）记账——
     * 同锈蚟级的多个连通块出力在 glue 层相加后一并喂入。</p>
     *
     * <p>v18：这套 EMA 兼任「本锈级功率」读数口径（tooltip / 仪表盘），
     * 旧的容器总账 EMA 已随锈级分账拆除。</p>
     */
    private final double[] emaPowerByOxidation = new double[PowerMath.OXIDATION_LEVELS];

    private long tickCounter;
    private long lastTickTime = System.currentTimeMillis();

    public GeneratorState getOrCreateGenerator(int slot) {
        return generators.computeIfAbsent(slot, key -> new GeneratorState());
    }

    public GeneratorState getGenerator(int slot) {
        return generators.get(slot);
    }

    public SignalTracker getOrCreateSignalTracker(int slot) {
        return signalTrackers.computeIfAbsent(slot, key -> new SignalTracker());
    }

    public SignalTracker getSignalTracker(int slot) {
        return signalTrackers.get(slot);
    }

    /** 获取或创建铜块网络边信号跟踪器 */
    public SignalTracker getOrCreateEdgeTracker(long edgeKey) {
        return edgeTrackers.computeIfAbsent(edgeKey, k -> new SignalTracker());
    }

    /** 当前内部 tick 计数（事件时间戳用） */
    public long currentTick() {
        return tickCounter;
    }

    /** 每 tick 末尾调用（glue 在分配入灯之后）：tick 计数推进 + 心跳时间戳 */
    public void endTick() {
        tickCounter++;
        lastTickTime = System.currentTimeMillis();
    }

    // ── 按锈级功率读数（v18，取代旧容器总账 EMA）──

    /**
     * 指定锈级的 EMA 功率读数（RE/t，基础出力口径）。
     *
     * @param oxidation 锈蚀级（0~3）
     */
    public double getLevelEmaPowerRe(int oxidation) {
        if (oxidation < 0 || oxidation >= emaPowerByOxidation.length) return 0.0;
        return emaPowerByOxidation[oxidation];
    }

    /** 指定锈级的 EMA 功率换算为 FE/t（边界换算，K = 1/16） */
    public long getLevelEmaPowerFe(int oxidation) {
        return Math.round(getLevelEmaPowerRe(oxidation) * PowerMath.RE_TO_FE);
    }

    // ── 网络级共振（见 living-power-tech.md §3.7）──

    /**
     * 用本 tick 各锈蚟级的**基础**出力更新共振 EMA。
     *
     * <p>必须每 tick 调用（包括没有发电机的 tick），否则停止发电的锈蚟级其 EMA
     * 不会衰减，会一直被算作「活跃锈级」拖低平衡度。</p>
     *
     * @param baseReThisTick 各锈蚟级本 tick 基础发电量（RE，共振前）；
     *                       长度不足的部分按 0 处理
     */
    public void updateOxidationEma(long[] baseReThisTick) {
        for (int i = 0; i < emaPowerByOxidation.length; i++) {
            double v = (baseReThisTick != null && i < baseReThisTick.length)
                ? (double) baseReThisTick[i] : 0.0;
            emaPowerByOxidation[i] += (v - emaPowerByOxidation[i]) * EMA_ALPHA;
            // 指数衰减达不到 0，必须截断，否则停发的锈蚟级会被永久算作活跃锈级
            if (emaPowerByOxidation[i] < EMA_EPSILON) emaPowerByOxidation[i] = 0.0;
        }
    }

    /**
     * 共振倍率 R ∈ [1, 4]（= {@link PowerMath#resonanceFactor}）。
     *
     * <p>只读 {@link #emaPowerByOxidation}（基础值），不读本 tick 已乘过增益的
     * 发电量——这是「禁回代」铁律的落地点。</p>
     */
    public double resonanceFactor() {
        return PowerMath.resonanceFactor(emaPowerByOxidation);
    }

    /** 共振增益 R^{@link PowerMath#RESONANCE_EXPONENT}，作用于本 tick 基础发电量 */
    public double resonanceGain() {
        return PowerMath.resonanceGain(emaPowerByOxidation);
    }

    /** 平衡度 s ∈ [0, 1]（各锈级出力的接近程度，tooltip 诊断用） */
    public double resonanceBalance() {
        return PowerMath.balanceFactor(emaPowerByOxidation);
    }

    /** 当前有出力的锈蚟级数 N（活跃锈级数，1~4；0 表示无任何发电） */
    public int activeOxidationLevels() {
        int n = 0;
        for (double v : emaPowerByOxidation) {
            if (v > 0.0) n++;
        }
        return n;
    }

    /** 各锈蚟级基础出力 EMA 副本（测试 / 调试用） */
    public double[] getEmaPowerByOxidation() {
        return emaPowerByOxidation.clone();
    }

    /** 供过期清理使用（镜像 {@code ContainerRedstoneData.getLastTickTime}） */
    public long getLastTickTime() {
        return lastTickTime;
    }
}
