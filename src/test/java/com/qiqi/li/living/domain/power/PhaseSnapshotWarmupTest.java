package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.SignalTracker;

/**
 * 账本重生的相位连续性测试（2026-09-09 相位快照落盘 + 首拍无沿宽限）。
 *
 * <p>账本死亡路径：LRU 120s 回收 / 退出重进 / 跨存档搬运。两个修复：</p>
 * <ul>
 *   <li>warmup（首拍无沿宽限）：新账本默认 8 tick 不入账——防 prevEdgeGrid 空
 *       导致的「稳态电平全部伪装成上升沿」假沿风暴；</li>
 *   <li>PhaseSnapshot（锁相快照落盘）：退出前冻结 (P, φ, 距上跳 tick 数)，
 *       重进回填后相位无缝续接——相对相位（调相布局）不再重进洗牌。</li>
 * </ul>
 */
class PhaseSnapshotWarmupTest {

    // ═══════════ warmup（首拍无沿宽限）═══════════

    @Test
    @DisplayName("新账本默认进入 warmup：8 tick 内不计入相位事件")
    void newLedger_defaultsToWarmup() {
        ContainerPowerData power = new ContainerPowerData();
        assertTrue(power.inWarmup(), "新账本应默认宽限（防假沿风暴）");
        for (int i = 0; i < ContainerPowerData.WARMUP_TICKS; i++) {
            power.endTick();
        }
        assertFalse(power.inWarmup(), "8 tick 后宽限结束");
    }

    @Test
    @DisplayName("快照回填保留 warmup（2026-09-09 语义修正：宽限与快照互补而非互斥）")
    void restore_clearsWarmup() {
        ContainerPowerData source = new ContainerPowerData();
        source.clearWarmup();
        // 驱动一条 4t 边锁相——now 恒取账本 currentTick（真实路径口径）
        SignalTracker t = source.getOrCreateEdgeTracker(0L);
        for (int i = 0; i < 4; i++) {
            t.onRisingEdge(source.currentTick(), 4096);
            for (int k = 0; k < 4; k++) source.endTick();   // 每 4 tick 一跳
        }
        // 上跳在 currentTick-4，活跃窗口 64t 内
        PhaseSnapshot snapshot = PhaseSnapshot.capture(source, source.currentTick());
        assertFalse(snapshot.edges().isEmpty(), "应抓到锁相边");

        ContainerPowerData restored = new ContainerPowerData();   // 新账本默认 warmup
        assertTrue(restored.inWarmup());
        int n = snapshot.restoreInto(restored, restored.currentTick());
        assertEquals(snapshot.edges().size(), n);
        // 修正后：回填不再 clearWarmup——warmup 走完即可（首拍假沿由
        // hasEdgeHistory 根修拦截，warmup 只防快照未覆盖边的首跳过早入账）
        assertTrue(restored.inWarmup(), "回填后应保留宽限（互补双保险）");
    }

    // ═══════════ 快照捕获 ═══════════

    @Test
    @DisplayName("快照只收已锁相且存活的边：未锁相（<2 跳变）与死边不进快照")
    void capture_filtersUnlockedAndDead() {
        ContainerPowerData power = new ContainerPowerData();
        power.clearWarmup();

        // 活跃边：4t 周期持续跳（now 恒 = currentTick，真实路径口径）
        SignalTracker locked = power.getOrCreateEdgeTracker(1L);
        for (int i = 0; i < 3; i++) {
            locked.onRisingEdge(power.currentTick(), 4096);
            for (int k = 0; k < 4; k++) power.endTick();
        }
        // 未锁相边：只跳 1 次
        SignalTracker oneBeat = power.getOrCreateEdgeTracker(2L);
        oneBeat.onRisingEdge(power.currentTick(), 100);

        // 死边：锁相后停跳，让时间走过存活窗口（aliveWindow(4t)=32）
        SignalTracker dead = power.getOrCreateEdgeTracker(3L);
        for (int i = 0; i < 3; i++) {
            dead.onRisingEdge(power.currentTick(), 4096);
            for (int k = 0; k < 4; k++) power.endTick();
        }
        long deadLastRise = power.currentTick() - 4;
        for (int i = 0; i < 70; i++) power.endTick();   // 停跳 70t > aliveWindow(4t)=64
        // 活跃边最后再跳一次（保持在窗口内）
        locked.onRisingEdge(power.currentTick(), 4096);

        assertTrue(power.currentTick() - deadLastRise > PowerMath.aliveWindow(4),
            "前置：死边距上跳应超存活窗口");

        PhaseSnapshot snap = PhaseSnapshot.capture(power, power.currentTick());
        List<Long> keys = snap.edges().stream().map(PhaseSnapshot.EdgeEntry::edgeKey).toList();
        assertTrue(keys.contains(1L), "活跃边应进快照");
        assertFalse(keys.contains(2L), "未锁相边（<2 跳变）不进快照");
        assertFalse(keys.contains(3L), "死边（停跳超存活窗口 64t）不进快照");
    }

    // ═══════════ 快照回填：相位平移守恒 ═══════════

    @Test
    @DisplayName("相位平移守恒：回填后 offset 不变（相对相位跨会话保留）")
    void restore_preservesOffset() {
        // 源账本：4t 振荡器，φ = 12 mod 4 = 0；2t 振荡器 φ = 10 mod 2 = 0
        ContainerPowerData source = new ContainerPowerData();
        source.clearWarmup();
        // now 恒取 currentTick（真实路径口径）；4t 与 2t 两条边交错驱动
        SignalTracker t4 = source.getOrCreateEdgeTracker(100L);
        SignalTracker t2 = source.getOrCreateEdgeTracker(200L);
        for (int i = 0; i < 16; i++) {
            long tickNow = source.currentTick();
            if (i % 4 == 0) t4.onRisingEdge(tickNow, 4096);      // 4t 周期
            if (i % 2 == 0) t2.onRisingEdge(tickNow, 64);        // 2t 周期
            source.endTick();
        }
        // 尾部再各跳一次，保证两条边都在存活窗口内
        long tail = source.currentTick();
        t4.onRisingEdge(tail, 4096);
        t2.onRisingEdge(tail, 64);

        PhaseSnapshot snapshot = PhaseSnapshot.capture(source, source.currentTick());
        assertTrue(snapshot.edges().size() >= 2, "两条边都应锁相且存活");
        int phi4 = findEntry(snapshot, 100L).offset();
        int phi2 = findEntry(snapshot, 200L).offset();

        // 新账本（模拟重进）：tickCounter 从 0 重新起步，回填
        ContainerPowerData restored = new ContainerPowerData();
        snapshot.restoreInto(restored, restored.currentTick());

        SignalTracker r4 = restored.getOrCreateEdgeTracker(100L);
        SignalTracker r2 = restored.getOrCreateEdgeTracker(200L);
        assertEquals(phi4, r4.offset(), "4t 边的 φ 应跨会话保留");
        assertEquals(phi2, r2.offset(), "2t 边的 φ 应跨会话保留");
        assertEquals(4, r4.period());
        assertEquals(2, r2.period());
    }

    @Test
    @DisplayName("空快照回填：返回 0，warmup 保持（新容器首访行为）")
    void emptySnapshot_keepsWarmup() {
        ContainerPowerData power = new ContainerPowerData();
        int n = PhaseSnapshot.EMPTY.restoreInto(power, power.currentTick());
        assertEquals(0, n);
        assertTrue(power.inWarmup(), "空快照回填不清宽限（无历史相位可续）");
    }

    @Test
    @DisplayName("回填锚存活：回填后的边在首个真实跳变前不被逐 tick 快照降级（二次退出不丢相位）")
    void negativeAnchor_survivesRecapture() {
        // 源账本：4t 边锁相
        ContainerPowerData source = new ContainerPowerData();
        source.clearWarmup();
        SignalTracker t = source.getOrCreateEdgeTracker(7L);
        for (int i = 0; i < 4; i++) {
            t.onRisingEdge(source.currentTick(), 4096);
            for (int k = 0; k < 4; k++) source.endTick();
        }
        PhaseSnapshot snap1 = PhaseSnapshot.capture(source, source.currentTick());
        int phi = findEntry(snap1, 7L).offset();

        // 重进模拟：新账本回填（2026-09-11 锚定数学：anchor = now − ((now−φ) mod P)，
        // now=0 时锚 ∈ [0,P)，不再恒为负——「负锚」是旧平移公式的产物，已退役）
        ContainerPowerData restored = new ContainerPowerData();
        snap1.restoreInto(restored, restored.currentTick());
        SignalTracker rt = restored.getOrCreateEdgeTracker(7L);
        assertEquals(phi, rt.offset(), "回填后 φ 应立即等于快照 φ");

        // 回填后立刻二次退出（未等到首个真实跳变）：回填锚必须仍进快照
        PhaseSnapshot snap2 = PhaseSnapshot.capture(restored, restored.currentTick());
        assertTrue(snap2.edges().stream().anyMatch(e -> e.edgeKey() == 7L),
            "回填锚（possibly < now 的同余点）是合法状态，worthSaving 必须放行——误拒导致二次退出丢相位");
        assertEquals(phi, findEntry(snap2, 7L).offset(), "φ 经两次快照往返不变");
    }

    // ═══════════ 世界轴换轴（2026-09-11 第三轮修复）═══════════

    @Test
    @DisplayName("世界轴快照往返：capture@世界W → restore@世界W+1000 → φ 不变（重进场景直接守卫）")
    void worldAxis_roundTrip_offsetStable() {
        // 模拟真实重进：世界 tick 一直在走（W → W+1000），但容器账本是新的。
        // 旧轴（容器本地计数）下：快照的 φ 存在旧坐标系，回填到新坐标系后
        // 首个真实跳变立刻重锚 → φ 变。新轴（世界 game time）下：capture 与
        // restore 同坐标系 → φ 跨「退出→重进」恒定。
        long worldTickAtExit = 1_000_000L;          // 退出时的世界 tick（长跑存档）
        long worldTickAtRejoin = worldTickAtExit + 1_000L;   // 重进时的世界 tick

        // 退出前：世界轴上驱动 16t 周期振荡器锁相（φ = 14 的场景复刻）
        int P = 16;
        ContainerPowerData source = new ContainerPowerData();
        source.clearWarmup();
        SignalTracker t = source.getOrCreateEdgeTracker(5L);
        for (int i = 0; i < 5; i++) {
            t.onRisingEdge(worldTickAtExit - (5 - i) * P, 4096);   // 世界轴上每 P tick 一跳
        }
        int phiBefore = t.offset();
        assertTrue(phiBefore >= 0 && phiBefore < P, "前置：φ 在 [0, P)");

        PhaseSnapshot snap = PhaseSnapshot.capture(source, worldTickAtExit);
        int phiSnapshot = findEntry(snap, 5L).offset();
        assertEquals(phiBefore, phiSnapshot, "快照 φ 应与源一致（同轴 capture）");

        // 重进：新账本（tickCounter=0），回填用「重进时刻的世界 tick」
        ContainerPowerData rejoined = new ContainerPowerData();
        snap.restoreInto(rejoined, worldTickAtRejoin);
        SignalTracker rt = rejoined.getOrCreateEdgeTracker(5L);

        assertEquals(phiBefore, rt.offset(),
            "重进回填后 φ 必须与退出前一致（世界轴同坐标系）——旧容器本地轴在此必变");

        // 首个真实跳变：振荡器物理相位连续（中继器 delayTimer 落盘续跑），
        // 下跳发生在世界 tick ≡ φ (mod P) 处——验证首跳不破坏 φ
        long firstBeat = worldTickAtRejoin + Math.floorMod(phiBefore - worldTickAtRejoin, P);
        rt.onRisingEdge(firstBeat, 4096);
        assertEquals(P, rt.period(), "首跳 interval 应恰为 P（同轴平移正确）");
        assertEquals(phiBefore, rt.offset(), "首跳后 φ 仍与退出前一致——不再重锚");
    }

    @Test
    @DisplayName("回填后首个真实跳变：interval = P（φ 反推锚数学自洽，周期不被污染）")
    void negativeAnchor_firstRealBeat_intervalIsExactPeriod() {
        // 源：4t 边，末跳在 currentTick=16 → φ = 0；退出时刻距上跳 d=3 tick
        ContainerPowerData source = new ContainerPowerData();
        source.clearWarmup();
        SignalTracker t = source.getOrCreateEdgeTracker(9L);
        for (int i = 0; i < 4; i++) {
            t.onRisingEdge(source.currentTick(), 4096);
            for (int k = 0; k < 4; k++) source.endTick();
        }
        t.onRisingEdge(source.currentTick(), 4096);   // 末跳：currentTick=16 处，φ = 0
        for (int i = 0; i < 3; i++) source.endTick(); // d = 3 → capture 时 currentTick=19
        PhaseSnapshot snap = PhaseSnapshot.capture(source, source.currentTick());
        assertEquals(3, findEntry(snap, 9L).sinceRise(), "退出时刻距上跳 3 tick");
        assertEquals(0, findEntry(snap, 9L).offset(), "末跳在 t=16 → φ = 0");

        // 重进回填（2026-09-11 φ 反推锚）：now=0 → anchor = 0 − ((0−0) mod 4) = 0
        ContainerPowerData restored = new ContainerPowerData();
        snap.restoreInto(restored, restored.currentTick());
        SignalTracker rt = restored.getOrCreateEdgeTracker(9L);
        assertEquals(0, rt.offset(), "回填后 φ 立即等于快照 φ");

        // 振荡器物理连续：下跳发生在 ≡ φ (mod P) → t=4；interval = 4 − 0 = 4 = P ✓
        long firstBeat = 4;
        rt.onRisingEdge(firstBeat, 4096);
        assertEquals(4, rt.period(), "首跳 interval 应恰为 P=4（φ 反推锚正确），而非 P-d");
        assertEquals(0, rt.offset(), "φ 跨首跳保持 0——不再重锚");
    }

    private static PhaseSnapshot.EdgeEntry findEntry(PhaseSnapshot snap, long key) {
        for (PhaseSnapshot.EdgeEntry e : snap.edges()) {
            if (e.edgeKey() == key) return e;
        }
        throw new AssertionError("快照缺边 " + key);
    }
}
