package com.qiqi.li.living.domain.power;

/**
 * 红电发电数学工具 —— 全部纯函数，零 Minecraft 依赖（可单测）。
 *
 * <p>公式来源：docs/红电系统.md §3.4（v17.4，双因子模型）：</p>
 * <pre>
 *   单次跳变能量(RE) = |Δsignal| × n^(1+解锁度) × P
 *   解锁度           = 调谐效率 × 波形规律度
 *   调谐效率         = (1 + cos θ) / 2，θ = (tick误差 / 偏好周期) × 2π
 *   FE               = RE × K，K = 1/16（全 mod 唯一标尺常量）
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
     * <p>K = 1/16 时数值与「单次能量 = |Δ| × 合因子 × (P/16)」的原始设计完全一致。
     * 电池容量 / IEnergyStorage 定稿后如需整体调产量，只改这一个数。</p>
     */
    public static final double RE_TO_FE = 1.0 / 16.0;

    /**
     * 涂蜡铜灯单位容量（每盏，FE）——与 K 并列的第二个标定常数。
     *
     * <p>容量涌现为线性「每盏 C × count」：与信号上限（堆叠数²）同用堆叠旋钮，
     * 无查表；平方容量在拆分时坍缩毁电，故取线性（§3.6 v17.5）。</p>
     */
    public static final long BULB_UNIT_CAPACITY_FE = 100;
    /** 每盏容量（1/1000 FE 定点，充电分配用） */
    public static final long BULB_UNIT_CAPACITY_MFE = BULB_UNIT_CAPACITY_FE * 1000;

    /**
     * 锈蚀 → 感应耦合管径（§3.5）。
     *
     * <p>未锈蚀 1.0 ＞ 斑驳 0.7 ＞ 风化 0.5 ＞ 氧化 0.35。
     * 管径决定发电机之间感应能量的分配份额，锈蚀是路由材料而非等级。</p>
     */
    public static double coupling(int oxidationLevel) {
        return switch (Math.max(0, Math.min(3, oxidationLevel))) {
            case 0 -> 1.0;
            case 1 -> 0.7;
            case 2 -> 0.5;
            default -> 0.35;
        };
    }

    /**
     * 调谐效率 = (1 + cos θ) / 2，θ = (tick误差 / 偏好周期) × 2π。
     *
     * <p>完美匹配 → 1，完全反相 → 0；结果 clamp [0,1]。
     * 偏好周期 &lt; 2（宽带堆 1 叠）时直接返回 0（效率固定，见 §3.4 因子二）。</p>
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
     * 相位质量合因子 = n^(1 + 解锁度)，解锁度 = 调谐效率 × 波形规律度。
     *
     * <p>解锁度 0（失谐 / 杂讯 / 宽带）→ 线性 n（布线保底）；
     * 解锁度 1（完美调谐 + 稳定时钟）→ 平方 n²（涌现天花板）。</p>
     */
    public static double combinedFactor(int n, double unlock) {
        if (n <= 0) return 0;
        double u = Math.max(0.0, Math.min(1.0, unlock));
        return Math.pow(n, 1.0 + u);
    }

    /**
     * 单次跳变能量（RE）= |Δ| × 合因子 × P。
     *
     * <p>自然单位公式，零硬常数。P = 跳变所在路径的周期估计（tick）；
     * 任何一项非正（杂讯路径 / 静止）都不产出。</p>
     */
    public static long eventEnergyRe(double delta, double factor, double periodTicks) {
        if (delta <= 0 || factor <= 0 || periodTicks <= 0) return 0;
        return Math.round(delta * factor * periodTicks);
    }

    /** RE → FE（边界换算，K = {@link #RE_TO_FE}） */
    public static long reToFe(long re) {
        return Math.round(re * RE_TO_FE);
    }
}
