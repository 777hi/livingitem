package com.qiqi.li.living.domain.power;

/**
 * 单槽位 / 单边的振荡器信号跟踪器 —— 检测上升沿、估计周期和偏移（跨 tick 持久）。
 *
 * <p><b>2026-10-09 power 收口 步骤 4 第 4 刀</b>：从 {@code LivingWaxedCopperFunction}
 * 的<b>内部类提升为顶层类</b>（纯搬迁，零逻辑改动）。理由：它被三方共用 ——
 * {@link ContainerPowerData}（持有槽位 / 边两张 tracker 表）、{@link PhaseInterpreter}
 * （读锁相波形做相位解读）、{@code LivingWaxedCopperFunction}（tick 里记录上升沿）。
 * 留在功能类里会让前两方反向依赖功能类；其中 {@code ContainerPowerData} 是
 * <b>容器级数据结构</b>，依赖「某个功能类」属明确的层内倒挂。</p>
 *
 * <p><b>两种用途</b>（同一实现、不同边）：</p>
 * <ul>
 *   <li><b>槽位跟踪器</b>（{@code ContainerPowerData.signalTrackers}）：按槽位跟踪振荡器</li>
 *   <li><b>边跟踪器</b>（{@code ContainerPowerData.edgeTrackers}）：按 {@code edgeKey}
 *       跟踪铜块网络的每条边；{@code FALLING_BIT} 置位者是<b>下降沿专用</b>
 *       （裂相器读它，见 {@link PhaseInterpreter}）</li>
 * </ul>
 */
public class SignalTracker {
    private int lastValue;
    private long lastRisingTick = -1;
    private long prevRisingTick = -1;
    private double periodTicks;
    private int intervalsSeen;
    private int lastDelta;

    /** 记录一次值变化（用于检测上升沿，外部已判定 delta > 0 才调用） */
    public void onRisingEdge(long tick, int delta) {
        lastDelta = delta;
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

    /** 周期估计（tick）；少于 2 个间隔则 0 */
    public int period() {
        if (intervalsSeen < 1 || periodTicks < 1.5) return 0;
        return (int) Math.round(periodTicks);
    }

    /** 在当前周期内的相位偏移（0 ~ period-1） */
    public int offset() {
        if (period() <= 0 || lastRisingTick < 0) return 0;
        // floorMod：lastRisingTick 可为负（相位快照回填平移到 tickCounter 起点之前），
        // Java % 对负数返回负值会让 φ 落到 (−P, 0)——floorMod 保证 [0, P)
        return Math.floorMod((int) lastRisingTick, period());
    }

    /** 上次跳变幅度 */
    public int lastDelta() {
        return lastDelta;
    }

    /** 最近一次上升沿的 tick（-1 = 尚无）；派生解读的活性判定用（v19） */
    public long lastRisingTick() {
        return lastRisingTick;
    }

    /**
     * 相位快照回填（2026-09-09；2026-09-11 第三轮：锚由调用方以 φ 反推）。
     *
     * <p>锚定数学上移到 {@code PhaseSnapshot.restoreInto}（那里才有 now）：
     * {@code anchor = now − floorMod(now − φ, P)}——「now 之前最近的 φ 同余点」。
     * 本方法只负责落值：offset() 即刻等于快照 φ、intervalsSeen 置 1（已锁相
     * 状态）、下一个真实跳变的 interval 恰为 P。若快照与当前线路不符（离线
     * 改线/换槽位），错误初值 2~3 个周期内被真实跳变覆盖自愈——磁盘账本
     * 不会长期说谎。</p>
     */
    public void restoreLocked(int periodTicks, int offset, long lastRisingTick, int delta) {
        this.periodTicks = periodTicks;
        this.intervalsSeen = 1;
        this.lastRisingTick = lastRisingTick;   // 调用方已按 φ 反推校正好
        this.prevRisingTick = lastRisingTick - periodTicks;
        this.lastDelta = delta;
    }
}
