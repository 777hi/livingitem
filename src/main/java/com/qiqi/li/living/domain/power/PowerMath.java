package com.qiqi.li.living.domain.power;

/**
 * 红电发电数学工具 —— 全部纯函数，零 Minecraft 依赖（可单测）。
 *
 * <p>公式（v3 —— 相位事件总线）：</p>
 * <pre>
 *   eff_δ_sum       = Σ√|Δ_i|                            // 各不同偏移的 √|Δ| 求和
 *   解锁度 u         = 调谐效率 × (n / 偏好周期)           // [0, 1]
 *   调谐效率         = (1 + cos θ) / 2，θ = (tick误差 / 偏好周期) × 2π
 *   合因子           = (eff_δ_sum)^(1+u)                  // 不含 P
 *   单次跳变能量(RE) = 合因子 × P
 *   FE               = RE × K，K = 1/16
 * </pre>
 *
 * <p>RE 为内部自然单位（零常数），K 只在边界（电池 / IEnergyStorage）出现一次。</p>
 */
public final class PowerMath {

    private PowerMath() {
    }

    /**
     * RE → FE 边界换算常量（全 mod 唯一标尺）。
     *
     * <p>K = 1/16，电池容量 / IEnergyStorage 定稿后如需整体调产量，只改这一个数。</p>
     */
    public static final double RE_TO_FE = 1.0 / 16.0;

    /**
     * 涂蜡铜灯单位容量（每盏，FE）——与 K 并列的第二个标定常数。
     *
     * <p>容量涌现为线性「每盏 C × count」：与信号上限（堆叠数²）同用堆叠旋钮，
     * 无查表；平方容量在拆分时坍缩毁电，故取线性（§3.6 v17.5）。</p>
     */
    public static final long BULB_UNIT_CAPACITY_FE = 1000;
    /** 每盏容量（1/1000 FE 定点，充电分配用） */
    public static final long BULB_UNIT_CAPACITY_MFE = BULB_UNIT_CAPACITY_FE * 1000;

    /**
     * 调谐效率 = (1 + cos θ) / 2，θ = (tick误差 / 偏好周期) × 2π。
     *
     * <p>完美匹配 → 1，完全反相 → 0；结果 clamp [0,1]。
     * 偏好周期 &lt; 2（宽带堆 1 叠）时直接返回 0（效率固定）。</p>
     *
     * @param tickError       |输入周期 − 偏好周期|
     * @param preferredPeriod 发电机偏好周期（= 堆叠数）
     */
    public static double tuningEfficiency(double tickError, double preferredPeriod) {
        if (preferredPeriod < 2) return 0;
        double theta = (Math.abs(tickError) / preferredPeriod) * 2.0 * Math.PI;
        double eff = (1.0 + Math.cos(theta)) / 2.0;
        return Math.max(0.0, Math.min(1.0, eff));
    }

    /**
     * 合因子 = (eff_δ_sum)^(1+u)，u = 解锁度。
     *
     * <p>u=0（失谐/宽带）→ 线性 eff_δ_sum（保底）；
     * u=1（完美调谐 + n=偏好周期）→ 平方 (eff_δ_sum)²（天花板）。</p>
     *
     * @param effDeltaSum Σ√|Δ_i|，各不同偏移的 √|Δ| 求和
     * @param n           相数（同周期域内互不同相的路数，仅用于 u 计算）
     * @param unlock      解锁度 [0, 1]
     */
    public static double combinedFactor(double effDeltaSum, int n, double unlock) {
        if (effDeltaSum <= 0 || n <= 0) return 0;
        double u = Math.max(0.0, Math.min(1.0, unlock));
        return Math.pow(effDeltaSum, 1.0 + u);
    }

    /**
     * 单次跳变能量（RE）= 合因子 × P。
     *
     * <p>P = 跳变所在路径的周期估计（tick）；
     * 任何一项非正（杂讯路径 / 静止）都不产出。</p>
     */
    public static long eventEnergyRe(double factor, double periodTicks) {
        if (factor <= 0 || periodTicks <= 0) return 0;
        return Math.round(factor * periodTicks);
    }

    /** RE → FE（边界换算，K = {@link #RE_TO_FE}） */
    public static long reToFe(long re) {
        return Math.round(re * RE_TO_FE);
    }

    /**
     * 把浮点值量化到指定有效数字位数（用于遥测快照，降低稳态下的「脏写」频率）。
     *
     * <p>EMA 类读数（共振增益 / 平衡度）会无限逼近目标值、每 tick 仍有一丢丢变化，
     * 若直接写进 DataComponent 会导致 {@code equals} 永远不等 → 每 tick 都标脏同步。
     * 量化到 2~3 位有效数字后，稳态值会「钉」在一个固定数上不再变，从而跳过脏写。
     * 复用现有 {@code dirtySlots} 批处理，不另造轮子。</p>
     *
     * @param value            待量化值（0 / NaN / Inf 原样返回）
     * @param significantFigures 有效数字位数（如 3 → 12.345 → 12.3；0.001234 → 0.00123）
     */
    public static double quantize(double value, int significantFigures) {
        if (value == 0.0 || Double.isNaN(value) || Double.isInfinite(value)) return value;
        int exp = (int) Math.floor(Math.log10(Math.abs(value)));
        double factor = Math.pow(10, significantFigures - 1 - exp);
        return Math.round(value * factor) / factor;
    }

    // ── 网络级共振（不同锈蚟级之间的「和声」，见 living-power-tech.md §3.7）──
    //
    // 块级感应是「铜块采样边信号」，网络级共振是「锈蚟级之间互相感应」——同一套
    // 机制抬高一个维度。锈级共振单位是**锈蚟级**（不是 BFS 连通块）：同锈蚟级的多个
    // 连通块出力直接相加。这一点不只是简化，更是 R ≤ OXIDATION_LEVELS 的结构性
    // 前提——否则把同一锈蚟级拆成多个小簇即可刷 N，退化为 N×(N-1) 膨胀。

    /**
     * 共振指数：容器总发电量乘 R 的该次方。
     *
     * <p>实测调产量时只改这一个数（2.0 → 1.5 可把满共振从 ×16 降到 ×8）。
     * 与 K、C 一样属于标定常数，但**不参与**公式内部结构（改它只缩放强度，
     * 不改变任何边界性质）。</p>
     */
    public static final double RESONANCE_EXPONENT = 2.0;

    /** 锈蚟级数（= 活跃锈级上限，也就是 R 的结构性上界） */
    public static final int OXIDATION_LEVELS = 4;

    /**
     * 平衡度 s = 几何平均 ÷ 算术平均（AM-GM）。
     *
     * <p>各锈级出力全部相等时 s = 1；一个独大时 s → 0。尺度无关——只关心各锈级
     * 的比例，不关心绝对值（1000/1000 与 1/1 同样共振）。</p>
     *
     * @param values 各锈蚟级的出力（只取正值参与；非正值视为该锈级不存在）
     * @return [0, 1]；有效锈级数 ≤ 1 时返回 1（无失衡可言）
     */
    public static double balanceFactor(double[] values) {
        if (values == null || values.length == 0) return 1.0;
        double logSum = 0.0;
        double sum = 0.0;
        int n = 0;
        for (double v : values) {
            if (v <= 0.0) continue;
            logSum += Math.log(v);
            sum += v;
            n++;
        }
        if (n <= 1) return 1.0;
        double am = sum / n;
        if (am <= 0.0) return 0.0;
        double gm = Math.exp(logSum / n);
        double s = gm / am;
        return Math.max(0.0, Math.min(1.0, s));
    }

    /**
     * 共振倍率 {@code R = 1 + (N − 1) × s}。
     *
     * <p>N = 有出力的锈蚟级数。s=1（各网出力相等）时 R = N；s→0（一个独大）时
     * R → 1。恒有 {@code R ∈ [1, N] ⊆ [1, OXIDATION_LEVELS]}：下限 1 保证共振
     * 永远不会「扣发电量」，上界由锈蚟级数结构性封顶，不可能失控。</p>
     *
     * @param powerByOxidation 各锈蚟级的出力（长度 ≤ {@link #OXIDATION_LEVELS}）
     */
    public static double resonanceFactor(double[] powerByOxidation) {
        if (powerByOxidation == null || powerByOxidation.length == 0) return 1.0;
        int n = 0;
        for (double v : powerByOxidation) {
            if (v > 0.0) n++;
        }
        if (n <= 1) return 1.0;
        // 结构性上界：锈级数不可能超过锈蚟级数
        n = Math.min(n, OXIDATION_LEVELS);
        double s = balanceFactor(powerByOxidation);
        double r = 1.0 + (n - 1) * s;
        return Math.max(1.0, Math.min(n, r));
    }

    /**
     * 共振增益 = R^{@link #RESONANCE_EXPONENT}，作用于容器本 tick 的**基础**发电量。
     *
     * <p>⚠️ 铁律：入参必须是「共振前」的各锈蚟级出力。若喂入乘过增益的值，会形成
     * {@code 增益↑ → 出力↑ → 增益↑} 的回代环。调用方
     * {@link ContainerPowerData} 的 EMA 只跟踪基础出力，整条链路为纯前馈。</p>
     */
    public static double resonanceGain(double[] powerByOxidation) {
        double r = resonanceFactor(powerByOxidation);
        if (r <= 1.0) return 1.0;
        return Math.pow(r, RESONANCE_EXPONENT);
    }
}