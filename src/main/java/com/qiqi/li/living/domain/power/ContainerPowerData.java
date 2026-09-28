package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    {
        // 新账本默认进入采样起步期（首拍无沿宽限）：首个上升沿不锚定相位、不入账。
        // 相位快照回填路径（restoreFromSnapshot）会显式清零。
        warmupTicksRemaining = WARMUP_TICKS;
    }

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
     * 派生相位注册表：slot → 该槽位相位元件的驻波条目（跨 tick 持久，v19 相位解读三元件）。
     *
     * <p>注入：电力层 BFS 访问到该槽位时，{@code now ≡ offset (mod period)} 的驻波
     * 视为上升沿注入通道；解读：三形态元件每 tick 重算自己的条目（读上一 tick 的
     * 全表——两阶段提交，无槽位处理顺序依赖）。失活驻波由 {@link #pruneRegistry} 修剪。</p>
     */
    private final Map<Integer, List<DerivedPhase>> phaseRegistry = new HashMap<>();

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

    /**
     * 各锈蚟级的**显示均值**（v19.1：tooltip「本锈级功率」、锈级柱状图，
     * 以及**网络级共振的读数口径**——平衡度 s / 共振增益 / 活跃锈级数）。
     *
     * <p>跳变门控记账是脉冲式的，快 EMA 对高频信号有峰谷纹波 → 平衡度与增益
     * 逐 tick 波动、tooltip 闪烁。共振读数用 32 tick 固定窗口均值：稳态零纹波。
     * 三条铁律结构不变——窗口只吃基础出力（单遍前馈、无回灌），且结算即精确
     * 归零（比 EMA_EPSILON 截断更干净，停发锈级 32 tick 内自愈）。</p>
     */
    private final double[] displayEmaByOxidation = new double[PowerMath.OXIDATION_LEVELS];
    private final double[] displayWindowAcc = new double[PowerMath.OXIDATION_LEVELS];
    private int displayWindowTicks;

    private long tickCounter;
    private long lastTickTime = System.currentTimeMillis();

    /**
     * 采样起步期标志（2026-09-09 首拍无沿宽限）。
     *
     * <p>账本重建（LRU 回收 / 退出重进 / 区块卸载超时）后首 tick，
     * {@code prevEdgeGrid} 为空 → 稳态电平全部被误判为「0 → 信号」的假上升沿风暴，
     * 多路相位被重载时刻的拓扑重新锚定（洗牌）。起步期（前 {@link #WARMUP_TICKS}
     * tick）电力层照常跟踪边信号（重建周期估计）但<strong>不向通道注入相位事件、
     * 不入账</strong>——多路振荡器的真实跳变到来后自然确立相位，周期估计完整
     * （每边跟踪器在 warmup 内至少见到 2 次真实跳变即可锁相）。</p>
     *
     * <p>注意只影响「电力层的相位/入账」——红石层传播（edgeGrid 重算、火把/中继器
     * 驱动）不受此标志影响，首个 tick 电网即恢复真实运行。</p>
     */
    private int warmupTicksRemaining;

    /** 采样起步期长度：覆盖最短锁相需求（2 次跳变间隔）+ 传播重建余量 */
    public static final int WARMUP_TICKS = 8;

    /** 是否处于采样起步期（账本重建后的宽限期） */
    public boolean inWarmup() {
        return warmupTicksRemaining > 0;
    }

    /** 起步期计数推进（每 tick 末调用；账本正常存活时恒为 0，零开销） */
    void advanceWarmup() {
        if (warmupTicksRemaining > 0) warmupTicksRemaining--;
    }

    /**
     * 手动设置起步期剩余 tick（测试 / 快照回填用）。
     * 回填相位快照后应置 0——快照已带历史相位，无需宽限。
     */
    void setWarmupTicks(int ticks) {
        warmupTicksRemaining = Math.max(0, ticks);
    }

    /** 清除起步期（相位快照回填后调用——快照已带历史相位，宽限反而白扔发电时间） */
    public void clearWarmup() {
        warmupTicksRemaining = 0;
    }

    /**
     * 遍历全部边跟踪器（相位快照抓取用，含下降沿命名空间）。
     * 仅供 {@link PhaseSnapshot#capture}，不暴露内部 Map 引用。
     */
    public void forEachEdgeTracker(java.util.function.BiConsumer<Long, SignalTracker> consumer) {
        edgeTrackers.forEach(consumer);
    }

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

    /** 只读获取边信号跟踪器（无则 null——派生解读的活性查询用，不造空条目） */
    public SignalTracker getEdgeTracker(long edgeKey) {
        return edgeTrackers.get(edgeKey);
    }

    // ── 派生相位注册表（v19 相位解读三元件）──

    /** 读某槽位的注册表驻波条目（无则空列表） */
    public List<DerivedPhase> getRegistry(int slot) {
        return phaseRegistry.getOrDefault(slot, List.of());
    }

    /** 覆写某槽位的注册表驻波条目（空列表 = 清除） */
    public void setRegistry(int slot, List<DerivedPhase> entries) {
        if (entries == null || entries.isEmpty()) phaseRegistry.remove(slot);
        else phaseRegistry.put(slot, entries);
    }

    /**
     * 全表修剪：清除超过存活窗口未再派生的驻波。
     *
     * <p>输入源停跳（或元件被替换成非相位元件）后，其派生驻波若不修剪，
     * 会经由注入持续刷新域内偏移的 lastSeenTick → 域永不超时 → 死源长期发电。
     * 存活窗口与域超时同口径（{@link PowerMath#aliveWindow}）。</p>
     */
    public void pruneRegistry(long now) {
        var it = phaseRegistry.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            List<DerivedPhase> kept = new ArrayList<>(e.getValue().size());
            for (DerivedPhase dp : e.getValue()) {
                if (now - dp.updatedTick() <= PowerMath.aliveWindow(dp.period())) kept.add(dp);
            }
            if (kept.isEmpty()) it.remove();
            else e.setValue(kept);
        }
    }

    /** 当前内部 tick 计数（事件时间戳用） */
    public long currentTick() {
        return tickCounter;
    }

    /** 每 tick 末尾调用（glue 在分配入灯之后）：tick 计数推进 + 心跳时间戳 + 起步期推进 */
    public void endTick() {
        tickCounter++;
        advanceWarmup();
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

    // ── 显示均值读数（v19.1：tooltip「本锈级功率」/ 锈级柱状图专用，无逐 tick 纹波）──

    /** 显示均值窗口长度（tick）——与 {@link #updateOxidationEma} 的结算周期一致 */
    public static final int DISPLAY_WINDOW_TICKS = 32;

    /** 指定锈级的显示均值功率（RE/t，浮点）。 */
    public double getLevelDisplayEmaPowerRe(int oxidation) {
        if (oxidation < 0 || oxidation >= displayEmaByOxidation.length) return 0.0;
        return displayEmaByOxidation[oxidation];
    }

    /** 指定锈级的显示均值功率换算为毫 FE（mFE 定点，K 换算 ×1000） */
    public long getLevelDisplayEmaPowerMilliFe(int oxidation) {
        return Math.round(getLevelDisplayEmaPowerRe(oxidation) * PowerMath.RE_TO_FE * 1000.0);
    }

    /** 各锈蚟级显示均值功率副本（毫 FE 定点，tooltip 锈级柱状图数据源） */
    public long[] getDisplayEmaByOxidationMilliFe() {
        long[] out = new long[displayEmaByOxidation.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.round(displayEmaByOxidation[i] * PowerMath.RE_TO_FE * 1000.0);
        }
        return out;
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

        // 显示均值窗口推进（v19.1：32 tick 固定窗口均值，tooltip 消纹波）
        for (int i = 0; i < displayEmaByOxidation.length; i++) {
            double v = (baseReThisTick != null && i < baseReThisTick.length)
                ? (double) baseReThisTick[i] : 0.0;
            displayWindowAcc[i] += v;
        }
        displayWindowTicks++;
        if (displayWindowTicks >= DISPLAY_WINDOW_TICKS) {
            for (int i = 0; i < displayEmaByOxidation.length; i++) {
                displayEmaByOxidation[i] = displayWindowAcc[i] / (double) DISPLAY_WINDOW_TICKS;
                displayWindowAcc[i] = 0.0;
            }
            displayWindowTicks = 0;
        }
    }

    /**
     * 共振倍率 R ∈ [1, 4]（= {@link PowerMath#resonanceFactor}）。
     *
     * <p>v19.1：读数口径从快记账 EMA 切到**显示窗口均值**——跳变门控下快 EMA 锯齿
     * 波动会让平衡度/增益逐 tick 闪烁。窗口只吃基础出力（{@link #updateOxidationEma}
     * 的入参），不读本 tick 已乘过增益的发电量——「禁回代」铁律的落地点不变；
     * 增益由平滑值算出、作用在本 tick 基础出力上（v18 设计语义）。</p>
     */
    public double resonanceFactor() {
        return PowerMath.resonanceFactor(displayEmaByOxidation);
    }

    /** 共振增益 R^{@link PowerMath#RESONANCE_EXPONENT}，作用于本 tick 基础发电量 */
    public double resonanceGain() {
        return PowerMath.resonanceGain(displayEmaByOxidation);
    }

    /** 平衡度 s ∈ [0, 1]（各锈级出力的接近程度，tooltip 诊断用） */
    public double resonanceBalance() {
        return PowerMath.balanceFactor(displayEmaByOxidation);
    }

    /**
     * 当前有出力的锈蚟级数 N（活跃锈级数，1~4；0 表示无任何发电）。
     *
     * <p>v19.1：读显示窗口均值——结算即精确归零，停发锈级 32 tick 内自愈，
     * 无需 EMA_EPSILON 截断（铁律 3 由结算清零天然满足）。</p>
     */
    public int activeOxidationLevels() {
        int n = 0;
        for (double v : displayEmaByOxidation) {
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
