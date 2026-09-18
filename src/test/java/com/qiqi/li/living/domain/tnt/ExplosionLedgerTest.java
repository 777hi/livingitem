package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 待炸账本守卫（2026-09-18）—— 「未观测的地形变更推迟到观测时」（原版 TNT 引信模型）。
 *
 * <p>钉的是**记账语义**，不是世界交互：实际破坏通过 {@link ExplosionLedger.ChunkApplier}
 * 注入成记录器，所以这些用例完全不需要真世界。三条最容易写错、写错就会**永久丢爆炸**的规则：</p>
 * <ol>
 *   <li><b>未加载 → 丢弃</b>（等 {@code ChunkEvent.Load} 重新登记），**不能留着轮询**；</li>
 *   <li><b>预算用尽 → carryOver</b>（这些区块已加载，不会再触发 Load 事件，丢了就永远不炸）；</li>
 *   <li><b>位图是唯一真相</b>（存档里的计数不可信，反序列化后必须按位图重算）。</li>
 * </ol>
 */
class ExplosionLedgerTest {

    private static final int CENTER_X = 8;
    private static final int CENTER_Z = 8;

    private static ExplosionParams params(double radius) {
        return new ExplosionParams(CENTER_X, 64, CENTER_Z, radius, ExplosionParams.Mode.NORMAL, true);
    }

    /** 全部已加载的替身（每个区块现造一个 mock，`getPos()` 指回自己）。 */
    private static ServerLevel allLoaded() {
        return mockLevel((x, z) -> true);
    }

    /** 全部未加载的替身。 */
    private static ServerLevel noneLoaded() {
        return mockLevel((x, z) -> false);
    }

    private static ServerLevel mockLevel(BiPredicate<Integer, Integer> loaded) {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.dimension()).thenReturn(Level.OVERWORLD);

        ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        Mockito.when(source.getChunkNow(Mockito.anyInt(), Mockito.anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0, Integer.class);
            int z = inv.getArgument(1, Integer.class);
            if (!loaded.test(x, z)) return null;
            LevelChunk chunk = Mockito.mock(LevelChunk.class);
            Mockito.when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
            return chunk;
        });
        Mockito.when(level.getChunkSource()).thenReturn(source);
        return level;
    }

    /** 记录"实际破坏"落到了哪些区块。 */
    private static final class Recorder implements ExplosionLedger.ChunkApplier {
        final List<ChunkPos> applied = new ArrayList<>();

        @Override
        public void apply(ServerLevel level, ExplosionParams params, LevelChunk chunk) {
            applied.add(chunk.getPos());
        }
    }

    // ---------- 1. 建档：圆外直接标记完成 ----------

    @Test
    @DisplayName("建档：圆外区块立即标记完成，只有圆内的进待处理队列（半径 16 → 正十字 5 格）")
    void schedule_marksOutOfCircleAsDone() {
        ExplosionLedger ledger = new ExplosionLedger();
        assertTrue(ledger.schedule(params(16), Level.OVERWORLD));

        assertEquals(1, ledger.entryCount());
        assertEquals(5, ledger.remainingOf(0), "3×3 网格里只有正十字 5 格命中圆形范围");
        assertEquals(5, ledger.pendingCheckCount(Level.OVERWORLD), "只有未完成的区块进待检查队列");
    }

    // ---------- 2. 未加载 → 丢弃（不轮询） ----------

    @Test
    @DisplayName("未加载区块 → 丢弃不轮询，条目保留等自然加载")
    void flush_dropsUnloadedChunks() {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(params(16), Level.OVERWORLD);

        ledger.flush(noneLoaded(), (l, p, c) -> fail("未加载的区块不该被应用"));

        assertEquals(0, ledger.pendingCheckCount(Level.OVERWORLD),
            "未加载 → 丢弃（否则每 tick 都要重扫几百个未加载区块）；靠 ChunkEvent.Load 重新登记");
        assertEquals(1, ledger.entryCount(), "条目必须保留 —— 它记录着'这场爆炸还没炸完'");
        assertEquals(5, ledger.remainingOf(0));
    }

    @Test
    @DisplayName("丢掉的区块在自然加载后重新登记 → 下一 tick 补炸（完整闭环）")
    void droppedChunk_isAppliedAfterNaturalLoad() {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(params(16), Level.OVERWORLD);
        ledger.flush(noneLoaded(), (l, p, c) -> fail("未加载时不应用"));
        assertEquals(0, ledger.pendingCheckCount(Level.OVERWORLD));

        // 模拟 ChunkEvent.Load → onChunkLoaded 只登记坐标
        ChunkPos target = new ChunkPos(0, 0);
        ledger.scheduleCheck(Level.OVERWORLD, target);

        Recorder recorder = new Recorder();
        ledger.flush(allLoaded(), recorder);

        assertEquals(List.of(target), recorder.applied, "玩家走过去 → 区块加载 → 破坏补上");
        assertEquals(4, ledger.remainingOf(0), "位图推进一位");
    }

    // ---------- 3. 预算用尽 → carryOver（丢了就永远不炸） ----------

    @Test
    @DisplayName("分帧预算：单 tick 只处理 MAX_CHUNKS_PER_TICK 个，剩余 carryOver 到下一 tick")
    void flush_isBudgeted_remainderCarriedOver() {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(params(64), Level.OVERWORLD); // chunkRadius=4 ⇒ 圆内 49 格 > 预算 32

        int affected = ledger.pendingCheckCount(Level.OVERWORLD);
        assertTrue(affected > ExplosionLedger.MAX_CHUNKS_PER_TICK,
            "用例前提：受影响区块要多于预算，实测 " + affected);

        Recorder first = new Recorder();
        ledger.flush(allLoaded(), first);

        assertEquals(ExplosionLedger.MAX_CHUNKS_PER_TICK, first.applied.size(),
            "单 tick 只处理预算内的区块（限速安全阀）");
        assertEquals(affected - ExplosionLedger.MAX_CHUNKS_PER_TICK,
            ledger.pendingCheckCount(Level.OVERWORLD),
            "剩余必须 carryOver —— 这些区块已加载，不会再触发 ChunkEvent.Load，丢了就永远不炸");
        assertEquals(1, ledger.entryCount());

        Recorder second = new Recorder();
        ledger.flush(allLoaded(), second);
        assertEquals(0, ledger.pendingCheckCount(Level.OVERWORLD));
        assertEquals(0, ledger.entryCount(), "全部完成 → 条目移除（账本自动收敛）");
        assertEquals(affected, first.applied.size() + second.applied.size(), "两次合起来正好覆盖全部");
    }

    // ---------- 5. 多场爆炸叠加 ----------

    @Test
    @DisplayName("多场爆炸叠加：重叠区块对每条条目各处理一次（破坏幂等），两条目各自收敛")
    void overlappingEntries_eachAppliedOncePerEntry() {
        ExplosionLedger ledger = new ExplosionLedger();
        // 不同等级 + 不同网格：SUPER 半径 64（9×9 网格）/ NORMAL 半径 16（3×3 网格）
        ledger.schedule(new ExplosionParams(CENTER_X, 64, CENTER_Z, 64,
            ExplosionParams.Mode.SUPER, true), Level.OVERWORLD);
        ledger.schedule(new ExplosionParams(CENTER_X, 64, CENTER_Z, 16,
            ExplosionParams.Mode.NORMAL, true), Level.OVERWORLD);
        assertEquals(2, ledger.entryCount());

        Recorder recorder = new Recorder();
        for (int pass = 0; pass < 6 && ledger.entryCount() > 0; pass++) {
            ledger.flush(allLoaded(), recorder);
        }

        long centerHits = recorder.applied.stream().filter(new ChunkPos(0, 0)::equals).count();
        assertEquals(2, centerHits,
            "重叠区块对每条条目各应用一次 —— 破坏必须幂等（第二次看到的已是空气）");
        assertEquals(0, ledger.entryCount(), "两场爆炸都应各自收敛");
    }

    @Test
    @DisplayName("flush 期间新登记的区块不被覆盖（同 tick 又引爆一场）")
    void newRegistrationsDuringFlushSurvive() {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(params(16), Level.OVERWORLD);

        ChunkPos late = new ChunkPos(99, 99);
        ExplosionLedger.ChunkApplier registering = (l, p, c) ->
            ledger.scheduleCheck(Level.OVERWORLD, late); // 模拟"处理过程中又引爆一场"

        ledger.flush(allLoaded(), registering);

        assertEquals(1, ledger.pendingCheckCount(Level.OVERWORLD),
            "flush 期间新登记的区块必须还在队列里 —— 它已加载、不会再触发 ChunkEvent.Load，丢了就永远不炸");

        ledger.flush(allLoaded(), new Recorder());
        assertEquals(0, ledger.pendingCheckCount(Level.OVERWORLD), "下一 tick 消化掉（不在任何条目范围内 → 直接出队）");
    }

    @Test
    @DisplayName("账本满：schedule 返回 false（调用方据此降级为「只炸已加载部分」，不静默丢爆炸）")
    void schedule_rejectsWhenFull() {
        ExplosionLedger ledger = new ExplosionLedger();
        for (int i = 0; i < ExplosionLedger.MAX_ENTRIES; i++) {
            assertTrue(ledger.schedule(params(16), Level.OVERWORLD), "第 " + i + " 条应能登记");
        }
        assertFalse(ledger.schedule(params(16), Level.OVERWORLD),
            "超出上限必须返回 false，让调用方走降级路径 —— 否则声光已播、方块没坏");
        assertEquals(ExplosionLedger.MAX_ENTRIES, ledger.entryCount());
    }

    // ---------- 6. 持久化：位图是唯一真相 ----------

    @Test
    @DisplayName("存档往返：参数与位图保持；remaining 按位图重算（不信存档里的计数）")
    void saveLoad_roundTrip() {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(params(64), Level.OVERWORLD);
        ledger.flush(allLoaded(), new Recorder()); // 处理掉一批，制造"部分完成"的状态
        int remainingBefore = ledger.remainingOf(0);
        int pendingBefore = ledger.pendingCheckCount(Level.OVERWORLD);

        CompoundTag tag = ledger.save(new CompoundTag(), null);
        ExplosionLedger restored = ExplosionLedger.load(tag, null);

        assertEquals(1, restored.entryCount());
        assertEquals(remainingBefore, restored.remainingOf(0), "剩余数必须按位图重算后一致");

        // 行为等价：把剩下的全部炸完，落点集合应与原账本继续推进一致
        Recorder rest = new Recorder();
        for (int i = 0; i < 8 && restored.entryCount() > 0; i++) {
            for (ChunkPos cp : restored.remainingChunks()) {
                restored.scheduleCheck(Level.OVERWORLD, cp);
            }
            restored.flush(allLoaded(), rest);
        }
        assertEquals(remainingBefore, rest.applied.size(), "恢复后能恰好补完剩余区块，不多不少");
        assertTrue(pendingBefore > 0);
    }

    @Test
    @DisplayName("空账本往返安全（没有待炸条目时存档不炸）")
    void saveLoad_emptyLedger() {
        CompoundTag tag = new ExplosionLedger().save(new CompoundTag(), null);
        assertNotNull(tag);
        assertEquals(0, ExplosionLedger.load(tag, null).entryCount());
    }
}
