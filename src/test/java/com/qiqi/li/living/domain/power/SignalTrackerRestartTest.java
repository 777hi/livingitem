package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A5（2026-10-27 定案）：振荡信号停机后恢复，**不把停机时长当周期，不补发停机期间的电**。
 *
 * <p><b>被守的行为</b>：上升沿间隔超过失活窗口（{@link PowerMath#aliveWindow}）时，
 * 旧周期作废、本次沿只当新起点；恢复后第二个沿重新得出周期，发电照常。
 * 修复前：停机间隔被 EMA 当成超长周期，恢复瞬间按垃圾周期入账。</p>
 *
 * <p>场景口径（简单三段）：① 正常运行锁相不受影响；② 停机 → 恢复 → 重新计时；
 * ③ 运行中正常改频（间隔变化但未超过窗口）⇒ 照常平滑跟随。</p>
 */
class SignalTrackerRestartTest {

    private static final int P = 20;          // 正常周期 20 tick
    private static final int DELTA = 4096;

    @Test
    @DisplayName("正常运行：两个沿后 period()==P（I-B5 既有语义不受 A5 影响）")
    void steadyRun_locksOntoPeriod() {
        SignalTracker t = new SignalTracker();
        long tick = 0;
        for (int i = 0; i < 6; i++) {
            tick += P;
            t.onRisingEdge(tick, DELTA);
        }
        assertEquals(P, t.period(), "恒定间隔锁相");
        assertEquals(DELTA, t.lastDelta());
    }

    @Test
    @DisplayName("停机后恢复：长间隔作废旧周期，恢复后第二个沿重新得出周期（不补发停机电量）")
    void stoppedThenResumed_relocksFromScratch() {
        SignalTracker t = new SignalTracker();
        long tick = 0;
        for (int i = 0; i < 6; i++) {
            tick += P;
            t.onRisingEdge(tick, DELTA);
        }
        assertEquals(P, t.period(), "前置：已锁相");

        // 停机 500 tick（> aliveWindow(20)=40）后恢复
        tick += 500;
        t.onRisingEdge(tick, DELTA);
        assertEquals(0, t.period(), "恢复沿只当新起点：旧周期作废、间隔不进 EMA");

        // 恢复后的第二个沿：间隔恢复为 P ⇒ 重新锁相
        tick += P;
        t.onRisingEdge(tick, DELTA);
        assertEquals(P, t.period(), "恢复后第二个沿重新得出周期，发电照常");
    }

    @Test
    @DisplayName("运行中正常改频（间隔变化未超窗口）⇒ EMA 照常平滑跟随，不误判停机")
    void frequencyChange_withinWindow_stillFollows() {
        SignalTracker t = new SignalTracker();
        long tick = 0;
        for (int i = 0; i < 6; i++) {
            tick += P;
            t.onRisingEdge(tick, DELTA);
        }
        assertEquals(P, t.period(), "前置：已锁相");

        // 改频到 30 tick：间隔 30 > 20 但 ≤ aliveWindow(20)=40 ⇒ 不算停机
        tick += 30;
        t.onRisingEdge(tick, DELTA);
        assertEquals(25, t.period(), "EMA 半衰：(20+30)/2 = 25");

        tick += 30;
        t.onRisingEdge(tick, DELTA);
        assertEquals(28, (int) Math.round(t.period()), "继续跟随新频率");
    }

    @Test
    @DisplayName("快照回填路径不受 A5 误伤：回填锚后下一个真实沿间隔恰为 P ≤ 窗口")
    void snapshotRestore_nextEdgeWithinWindow() {
        SignalTracker t = new SignalTracker();
        // 回填：锚 = now 之前最近的 φ 同余点（985，φ=floorMod(985,20)=5），
        // 振荡器在账本重建期间持续运行 ⇒ 下一个真实沿 = 锚 + P = 1005，间隔恰为 P
        t.restoreLocked(P, 5, /* lastRisingTick = 锚 */ 985, DELTA);
        assertEquals(P, t.period());

        long now = 1000;                      // 账本重建完成时刻
        t.onRisingEdge(now + 5, DELTA);       // 1005 = 锚 + P，无缝续接
        assertEquals(P, t.period(), "回填后接续锁相不被停机判据打断");
    }
}
