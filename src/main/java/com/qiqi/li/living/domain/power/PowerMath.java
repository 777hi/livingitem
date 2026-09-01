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
}