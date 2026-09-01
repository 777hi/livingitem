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
 * <p>v18.0：为切制（H/V 隔离）引入双通道 {@link #channelSecondary}，
 * 通过 {@link #channel(int)} 按轴索引访问：0=水平(主通道)，1=垂直(副通道)。</p>
 */
public class GeneratorState {

    /** EMA 平滑系数（约 8 tick 记忆） */
    private static final double EMA_ALPHA = 0.125;

    /** 偏好周期 = 堆叠数（§3.4 因子二）；0 表示未设置（<2 即宽带态） */
    private int preferredPeriod;

    /** 主通道（水平轴 / 全向）—— 接收铜块网络的全部相位事件 */
    private final ChannelState channelPrimary = new ChannelState();

    /** 副通道（垂直轴）—— 切制 H/V 隔离用，其余形态不用 */
    private final ChannelState channelSecondary = new ChannelState();

    /** 本 tick 发电量（RE） */
    private long generatedReThisTick;

    /** 本发电机的 EMA 功率（RE/t） */
    private double emaPowerRe;

    /**
     * 按轴索引取通道。
     * @param axis 0=水平（主通道，铜块/雕文/格栅也用此），1=垂直（副通道，仅切制用）
     */
    public ChannelState channel(int axis) {
        return axis == 0 ? channelPrimary : channelSecondary;
    }

    /** 主通道（等价于 {@code channel(0)}） */
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

    /** 取走本 tick 累计发电量（RE）并推进 EMA */
    public long drainAndEndTick() {
        long v = generatedReThisTick;
        generatedReThisTick = 0;
        emaPowerRe += (v - emaPowerRe) * EMA_ALPHA;
        return v;
    }

    /** 本发电机的 EMA 功率（RE/t） */
    public double getEmaPowerRe() {
        return emaPowerRe;
    }

    /** 本发电机的 EMA 功率换算为 FE/t */
    public long getEmaPowerFe() {
        return Math.round(emaPowerRe * PowerMath.RE_TO_FE);
    }
}