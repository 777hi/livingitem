package com.qiqi.li.living.domain.power;

/**
 * 单路输入波形状态 —— 一条物理信号路径（直连边或共享转发路径）。
 *
 * <p>维护该路的值、上升沿序列与周期估计（上升沿间隔 EMA）。
 * 周期与相位供「相位域分组」计 n，路自身是否稳定（{@link #hasUsablePhase()}）
 * 决定它能否参与相位域——乱按的路只有幅度、不进任何域。</p>
 */
public class PathState {

    private int lastValue;
    private long lastEventTick = -1;
    /** 16-bit 滚动窗口：bit0 = 最新 tick 的「值>0」，每 tick 左移一位（F3+H 波形显示用） */
    private int waveBits;
    /** 上次跳变幅度（|Δ|，信号单位——公式展示用） */
    private int lastDelta;

    private long lastRisingTick = -1;
    private long prevRisingTick = -1;
    private double periodTicks;          // 上升沿间隔 EMA；0 = 未知
    private int intervalsSeen;

    private int domainN;                 // 所属周期域的去重相数（0 = 未入域）

    /**
     * 记录一次值变化。
     *
     * @return 有符号变化量（0 = 无变化）；上升沿为正、下降沿为负
     */
    public int recordValue(long tick, int value) {
        // 波形窗口：glue 每 tick 喂值 → 每 tick 左移一位（时间对齐）
        waveBits = ((waveBits << 1) | (value > 0 ? 1 : 0)) & 0xFFFF;

        if (lastEventTick < 0) {
            // 首次见到该路：建立基线，不计跳变
            lastValue = value;
            lastEventTick = tick;
            return 0;
        }
        int delta = value - lastValue;
        if (delta == 0) return 0;

        if (delta > 0) {
            lastDelta = Math.abs(delta);
        }
        if (delta > 0) {
            if (lastRisingTick >= 0) {
                long interval = tick - lastRisingTick;
                if (interval > 0) {
                    periodTicks = intervalsSeen == 0
                        ? interval
                        : periodTicks + (interval - periodTicks) * 0.5;
                    intervalsSeen++;
                }
                prevRisingTick = lastRisingTick;
            }
            lastRisingTick = tick;
        }

        lastValue = value;
        lastEventTick = tick;
        return delta;
    }

    public int lastValue() {
        return lastValue;
    }

    /** 首次喂值的时间戳（-1 = 从未被喂过） */
    public long lastEventTick() {
        return lastEventTick;
    }

    public long lastRisingTick() {
        return lastRisingTick;
    }

    /** 周期估计（tick）；无上升沿时为 0 */
    public double periodTicks() {
        return periodTicks;
    }

    /** 周期取整；&lt;1.5（含未知）返回 0——1 tick「周期」即直流，不参与域 */
    public int roundedPeriod() {
        return periodTicks < 1.5 ? 0 : (int) Math.round(periodTicks);
    }

    /**
     * 是否具备入域资格：≥2 个上升沿间隔，且最近一个间隔与周期估计一致（±1 tick）。
     *
     * <p>抖动 / 乱按的路被挡在相位域之外——它们只贡献幅度，不抬升 n。</p>
     */
    public boolean hasUsablePhase() {
        if (intervalsSeen < 2 || lastRisingTick < 0 || prevRisingTick < 0) return false;
        return Math.abs((lastRisingTick - prevRisingTick) - periodTicks) <= 1.0;
    }

    public int domainN() {
        return domainN;
    }

    /** 16-bit 滚动波形窗口（bit0 = 最新 tick 的「值>0」） */
    public int waveBits() {
        return waveBits;
    }

    /** 上次跳变幅度（|Δ|，信号单位） */
    public int lastDelta() {
        return lastDelta;
    }

    void setDomainN(int domainN) {
        this.domainN = domainN;
    }
}
