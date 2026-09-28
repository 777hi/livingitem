package com.qiqi.li.living.domain.power;

/**
 * 单台发电机的发电状态（按槽位索引）。
 *
 * <p>v3 简化：不再按方向分组，单通道接收铜块网络的全部相位事件。
 * 形态由 {@link LivingWaxedCopperFunction} 的过滤策略决定。</p>
 *
 * <p>v17.7：新增 per-generator EMA 功率跟踪，使不同锈蚀/不同信号的发电机
 * 在 tooltip 中显示各自独立的功率读数。</p>
 *
 * <p>v19：切制 H/V 双通道（v18.0 引入的 {@code channelSecondary}）随「形态 =
 * 相位解读元件」重构退役——同周期偏移本就归同一个相位域，裂相相位进同一通道；
 * 通道重新回归单实例。</p>
 */
public class GeneratorState {

    /** EMA 平滑系数（约 8 tick 记忆） */
    private static final double EMA_ALPHA = 0.125;

    /** 偏好周期 = 堆叠数（§3.4 因子二）；0 表示未设置（<2 即宽带态） */
    private int preferredPeriod;

    /** 相位事件接收通道 —— 接收铜块网络的全部相位事件（真实采样 + 派生注入） */
    private final ChannelState channelPrimary = new ChannelState();

    /** 本 tick 发电量（RE） */
    private long generatedReThisTick;

    /** 本发电机的 EMA 功率（RE/t） */
    private double emaPowerRe;

    // ── 显示均值（v19.1：tooltip / 柱状图消纹波）──
    //
    // 跳变门控记账是脉冲式的（事件 tick 入账、间隔 tick 为 0），快 EMA（α=1/8）
    // 对高频信号的峰谷纹波超过量化精度 → tooltip 的 FE/t 数字高频闪烁。
    // 显示读数改用「与偏好周期对齐的窗口均值」：窗口取偏好周期的整倍数且 ≥32 tick，
    // 稳态下窗口内恰含整数个周期 → 均值恒定、零纹波；记账 EMA 保持快响应不动。

    /** 显示均值窗口（tick） */
    private int displayWindowLen;
    private long displayWindowAcc;
    private int displayWindowTicks;
    private double displayEmaRe;

    /** 显示均值窗口长度：ceil(32 / pref) × pref（偏好周期整倍数；宽带/异常按 1 处理 → 32） */
    private static int displayWindowLen(int preferredPeriod) {
        int p = Math.max(1, preferredPeriod);
        int cycles = Math.max(1, (32 + p - 1) / p);
        return p * cycles;
    }

    /** 相位事件接收通道 */
    public ChannelState channel() {
        return channelPrimary;
    }

    public int preferredPeriod() {
        return preferredPeriod;
    }

    /** 偏好周期 = 堆叠数，clamp [0, 64]（堆 1 叠 = 宽带，见 §3.4 因子二） */
    public void setPreferredPeriodFromStack(int stackCount) {
        this.preferredPeriod = Math.max(0, Math.min(64, stackCount));
    }

    /** 记录一次跳变产出的能量（RE）：累加为本 tick 发电量 */
    public void onEventEnergy(long re) {
        if (re <= 0) return;
        generatedReThisTick += re;
    }

    /** 取走本 tick 累计发电量（RE）并推进 EMA 与显示均值窗口 */
    public long drainAndEndTick() {
        long v = generatedReThisTick;
        generatedReThisTick = 0;
        emaPowerRe += (v - emaPowerRe) * EMA_ALPHA;

        // 显示均值窗口推进（窗口满即结算——停机后窗口均值自然衰减到 0，tooltip 自愈归零）
        if (displayWindowTicks == 0) {
            displayWindowLen = displayWindowLen(preferredPeriod);
        }
        displayWindowAcc += v;
        displayWindowTicks++;
        if (displayWindowTicks >= displayWindowLen) {
            displayEmaRe = (double) displayWindowAcc / displayWindowTicks;
            displayWindowAcc = 0;
            displayWindowTicks = 0;
        }
        return v;
    }

    /** 本发电机的 EMA 功率（RE/t）——记账口径（快响应，勿用于 tooltip） */
    public double getEmaPowerRe() {
        return emaPowerRe;
    }

    /** 显示均值功率（RE/t，浮点）——窗口均值，tooltip / 柱状图专用（无逐 tick 纹波） */
    public double getDisplayEmaPowerRe() {
        return displayEmaRe;
    }

    /** 显示均值功率换算为毫 FE（mFE 定点，K 换算 ×1000）——低于 1 FE/t 的发电也能显示 */
    public long getDisplayEmaPowerMilliFe() {
        return Math.round(displayEmaRe * PowerMath.RE_TO_FE * 1000.0);
    }

    /** 本发电机的 EMA 功率换算为 FE/t */
    public long getEmaPowerFe() {
        return Math.round(emaPowerRe * PowerMath.RE_TO_FE);
    }
}