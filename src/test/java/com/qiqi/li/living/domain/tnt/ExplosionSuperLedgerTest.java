package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.AttachmentHolder;

/** SUPER 参数账本、区块纪元水印与仅已加载降级队列的守卫。 */
class ExplosionSuperLedgerTest {
    private static final ChunkPos CENTER = new ChunkPos(0, 0);

    private static ExplosionParams superParams(double radius) {
        return new ExplosionParams(8, 64, 8, radius, ExplosionParams.Mode.SUPER, false);
    }

    private static ExplosionParams bitmapParams(double y) {
        return new ExplosionParams(8, y, 8, 1, ExplosionParams.Mode.NORMAL, true);
    }

    private static final class View implements ExplosionLedger.VisibleChunks {
        final Map<ChunkPos, ExplosionLedger.VisibleChunk> states = new LinkedHashMap<>();
        final Map<LevelChunk, AtomicReference<Long>> epochs = new LinkedHashMap<>();
        final ServerLevel level = mock(ServerLevel.class);

        View() {
            when(level.dimension()).thenReturn(Level.OVERWORLD);
        }

        LevelChunk ready(ChunkPos pos) {
            LevelChunk chunk = mock(LevelChunk.class);
            AtomicReference<Long> epoch = new AtomicReference<>();
            when(chunk.getPos()).thenReturn(pos);
            when(chunk.getExistingDataOrNull(TntComponents.EXPLOSION_EPOCH)).thenAnswer(inv -> epoch.get());
            when(chunk.setData(eq(TntComponents.EXPLOSION_EPOCH), anyLong()))
                .thenAnswer(inv -> epoch.getAndSet(inv.getArgument(1, Long.class)));
            states.put(pos, new ExplosionLedger.VisibleChunk(new Object(), chunk));
            epochs.put(chunk, epoch);
            return chunk;
        }

        void waiting(ChunkPos pos) {
            states.put(pos, new ExplosionLedger.VisibleChunk(new Object(), null));
        }

        long watermark(LevelChunk chunk) {
            Long value = epochs.get(chunk).get();
            return value == null ? 0 : value;
        }

        @Override
        public Iterable<ChunkPos> snapshot() {
            return List.copyOf(states.keySet());
        }

        @Override
        public ExplosionLedger.VisibleChunk lookup(ChunkPos pos) {
            return states.getOrDefault(pos, new ExplosionLedger.VisibleChunk(null, null));
        }

        void flush(ExplosionLedger ledger, ExplosionLedger.ChunkApplier applier) {
            ledger.flush(level, applier, this);
        }

        boolean schedule(ExplosionLedger ledger, ExplosionParams params) {
            return ledger.schedule(params, Level.OVERWORLD, this);
        }
    }

    private static ExplosionLedger bitmapFull() {
        ExplosionLedger ledger = new ExplosionLedger();
        for (int i = 0; i < ExplosionLedger.MAX_ENTRIES; i++) {
            assertTrue(ledger.schedule(bitmapParams(i), Level.OVERWORLD));
        }
        return ledger;
    }

    private static ListTag savedEntries(ExplosionLedger ledger) {
        return ledger.save(new CompoundTag(), null).getList("entries", Tag.TAG_COMPOUND);
    }

    @Test
    void giantSuperNeverNeedsRepresentableBitmapAndSchedulesOnlyVisibleIntersections() {
        View view = new View();
        LevelChunk center = view.ready(CENTER);
        ChunkPos waiting = new ChunkPos(1, 0);
        view.waiting(waiting);
        ChunkPos outside = new ChunkPos(Integer.MAX_VALUE, Integer.MAX_VALUE);
        view.ready(outside);
        ExplosionParams giant = superParams(100_000_000);
        assertThrows(ArithmeticException.class, giant::chunkCount);
        ExplosionLedger ledger = new ExplosionLedger();
        assertTrue(view.schedule(ledger, giant));
        assertEquals(2, ledger.pendingCheckCount(Level.OVERWORLD));
        assertEquals(-1, ledger.remainingOf(0));
        assertTrue(ledger.remainingChunks().isEmpty());
        CompoundTag saved = ledger.save(new CompoundTag(), null);
        CompoundTag entry = saved.getList("entries", Tag.TAG_COMPOUND).getCompound(0);
        assertFalse(entry.contains("done"));
        assertEquals(1L, entry.getLong("epoch"));
        ExplosionLedger restored = ExplosionLedger.load(saved, null);
        List<ChunkPos> applied = new ArrayList<>();
        view.flush(restored, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(List.of(CENTER), applied);
        assertEquals(1L, view.watermark(center));
        assertEquals(1, restored.pendingCheckCount(Level.OVERWORLD));
        assertEquals(1, restored.entryCount());
    }

    @Test
    void persistentWatermarkSkipsReloadButNewEpochExplodesRebuiltTerrain() {
        View view = new View();
        LevelChunk chunk = view.ready(CENTER);
        ExplosionLedger ledger = new ExplosionLedger();
        List<ExplosionParams> applied = new ArrayList<>();
        assertTrue(view.schedule(ledger, superParams(1)));
        view.flush(ledger, (l, p, c) -> applied.add(p));
        assertEquals(1L, view.watermark(chunk));
        ledger.forgetChunk(Level.OVERWORLD, CENTER);
        ledger.scheduleCheck(Level.OVERWORLD, CENTER);
        view.flush(ledger, (l, p, c) -> applied.add(p));
        assertEquals(1, applied.size());
        ExplosionLedger restored = ExplosionLedger.load(ledger.save(new CompoundTag(), null), null);
        view.flush(restored, (l, p, c) -> applied.add(p));
        assertEquals(1, applied.size(), "bootstrap must respect saved chunk watermark");
        assertTrue(view.schedule(restored, superParams(2)));
        view.flush(restored, (l, p, c) -> applied.add(p));
        assertEquals(2, applied.size());
        assertEquals(2L, view.watermark(chunk));
        assertEquals(2, restored.entryCount(), "SUPER entries remain for unobserved chunks");
    }

    @Test
    void mixedRoundPreservesOrderAndCommitsOnlyAfterEveryEntryWithFixedOldWatermark() {
        View view = new View();
        LevelChunk chunk = view.ready(CENTER);
        ExplosionLedger ledger = new ExplosionLedger();
        ExplosionParams first = superParams(1);
        ExplosionParams ordinary = bitmapParams(65);
        ExplosionParams last = superParams(2);
        view.schedule(ledger, first);
        view.schedule(ledger, ordinary);
        view.schedule(ledger, last);
        List<ExplosionParams> order = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> {
            assertEquals(0L, view.watermark(c), "watermark must not advance per entry");
            order.add(p);
        });
        assertEquals(List.of(first, ordinary, last), order);
        assertEquals(3L, view.watermark(chunk));
        assertEquals(2, ledger.entryCount());
    }

    @Test
    void failureRetriesSuperRoundWithoutWatermarkButKeepsSuccessfulBitmapDirty() {
        View view = new View();
        LevelChunk chunk = view.ready(CENTER);
        ExplosionLedger ledger = new ExplosionLedger();
        ExplosionParams first = superParams(1);
        ExplosionParams ordinary = bitmapParams(65);
        ExplosionParams last = superParams(2);
        view.schedule(ledger, first);
        view.schedule(ledger, ordinary);
        view.schedule(ledger, last);
        ledger.setDirty(false);
        view.flush(ledger, (l, p, c) -> {
            if (p == last) throw new IllegalStateException("test failure");
        });
        assertEquals(0L, view.watermark(chunk));
        assertEquals(1, ledger.pendingCheckCount(Level.OVERWORLD));
        assertTrue(ledger.isDirty());
        assertEquals(2, ledger.entryCount(), "successful bitmap completion survives later failure");
        List<ExplosionParams> retry = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> retry.add(p));
        assertEquals(List.of(first, last), retry);
        assertEquals(3L, view.watermark(chunk));
    }

    @Test
    void sameCoordinateRegistrationDuringApplySurvivesSnapshotAndDoesNotAdvanceItsEpoch() {
        View view = new View();
        LevelChunk chunk = view.ready(CENTER);
        ExplosionLedger ledger = new ExplosionLedger();
        ExplosionParams first = superParams(1);
        ExplosionParams added = superParams(2);
        view.schedule(ledger, first);
        List<ExplosionParams> applied = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> {
            applied.add(p);
            assertTrue(view.schedule(ledger, added));
        });
        assertEquals(List.of(first), applied);
        assertEquals(1L, view.watermark(chunk));
        assertEquals(1, ledger.pendingCheckCount(Level.OVERWORLD));
        view.flush(ledger, (l, p, c) -> applied.add(p));
        assertEquals(List.of(first, added), applied);
        assertEquals(2L, view.watermark(chunk));
    }

    @Test
    void startSnapshotAndCoordinateBudgetBoundReentrantLoadedRegistrations() {
        View view = new View();
        for (int i = 0; i < 70; i++) view.ready(new ChunkPos(i, 0));
        ExplosionLedger ledger = new ExplosionLedger();
        view.schedule(ledger, superParams(100_000));
        ChunkPos late = new ChunkPos(90, 0);
        List<ChunkPos> applied = new ArrayList<>();
        AtomicBoolean added = new AtomicBoolean();
        view.flush(ledger, (l, p, c) -> {
            applied.add(c.getPos());
            if (added.compareAndSet(false, true)) {
                view.ready(late);
                ledger.scheduleCheck(Level.OVERWORLD, late);
            }
        });
        assertEquals(32, applied.size());
        assertFalse(applied.contains(late));
        assertEquals(39, ledger.pendingCheckCount(Level.OVERWORLD));
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(64, applied.size());
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(71, applied.size());
        assertEquals(0, ledger.pendingCheckCount(Level.OVERWORLD));
        assertEquals(1, ledger.entryCount());
    }

    @Test
    void waitingHoldersRotateBehindReadyCarryoverAndEventuallyApply() {
        View view = new View();
        ChunkPos waiting = new ChunkPos(0, 0);
        view.waiting(waiting);
        for (int i = 1; i <= 40; i++) view.ready(new ChunkPos(i, 0));
        ExplosionLedger ledger = new ExplosionLedger();
        view.schedule(ledger, superParams(100_000));
        List<ChunkPos> applied = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(32, applied.size());
        assertEquals(9, ledger.pendingCheckCount(Level.OVERWORLD));
        view.ready(waiting);
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(41, applied.size());
        assertEquals(waiting, applied.get(40), "WAITING rotates to FIFO tail, not ahead of carryover");
    }

    @Test
    void bootstrapAfterSaveCompletesOnlyUndoneBitmapChunksWithCustomRadius() {
        View view = new View();
        ExplosionParams ordinary = new ExplosionParams(8, 64, 8, 256, ExplosionParams.Mode.HIGH_YIELD, false);
        assertTrue(ordinary.chunkCount() > 961, "legitimate custom bitmap radius cannot be capped at old maximum");
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(ordinary, Level.OVERWORLD);
        for (ChunkPos cp : ledger.remainingChunks()) view.ready(cp);
        view.flush(ledger, (l, p, c) -> {});
        int remaining = ledger.remainingOf(0);
        ExplosionLedger restored = ExplosionLedger.load(ledger.save(new CompoundTag(), null), null);
        assertEquals(remaining, restored.remainingOf(0));
        List<ChunkPos> applied = new ArrayList<>();
        while (restored.entryCount() > 0) view.flush(restored, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(remaining, applied.size(), "restore bootstrap must not replay done bitmap bits");
        assertEquals(remaining, applied.stream().distinct().count());
    }

    @Test
    void emptyLedgerKeepsDurableEpochAndOverflowCannotWrap() {
        View view = new View();
        view.ready(CENTER);
        ExplosionLedger ledger = new ExplosionLedger();
        view.schedule(ledger, bitmapParams(64));
        view.flush(ledger, (l, p, c) -> {});
        assertEquals(0, ledger.entryCount());
        ExplosionLedger restored = ExplosionLedger.load(ledger.save(new CompoundTag(), null), null);
        view.schedule(restored, superParams(1));
        assertEquals(2L, savedEntries(restored).getCompound(0).getLong("epoch"));
        CompoundTag exhausted = new CompoundTag();
        exhausted.putLong("lastEpoch", Long.MAX_VALUE);
        ExplosionLedger noMoreEpochs = ExplosionLedger.load(exhausted, null);
        assertThrows(ArithmeticException.class, () -> view.schedule(noMoreEpochs, superParams(1)));
        assertEquals(0, noMoreEpochs.entryCount());
        assertEquals(Long.MAX_VALUE, noMoreEpochs.save(new CompoundTag(), null).getLong("lastEpoch"));
    }

    @Test
    void sharedCapRejectsBitmapAndEvictsOnlyOldestSuperForSuper() {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.schedule(bitmapParams(1), Level.OVERWORLD);
        ledger.schedule(superParams(1), Level.OVERWORLD);
        ledger.schedule(superParams(2), Level.OVERWORLD);
        for (int i = 3; i < ExplosionLedger.MAX_ENTRIES; i++) ledger.schedule(bitmapParams(i), Level.OVERWORLD);
        assertFalse(ledger.schedule(bitmapParams(9), Level.OVERWORLD));
        assertTrue(ledger.schedule(superParams(3), Level.OVERWORLD));
        assertEquals(ExplosionLedger.MAX_ENTRIES, ledger.entryCount());
        ListTag entries = savedEntries(ledger);
        assertEquals("NORMAL", entries.getCompound(0).getString("mode"));
        assertEquals(3L, entries.getCompound(1).getLong("epoch"));
        assertEquals(ExplosionLedger.MAX_ENTRIES + 1L, entries.getCompound(entries.size() - 1).getLong("epoch"));
        assertFalse(entries.getCompound(entries.size() - 1).contains("done"));
    }

    @Test
    void restoredOutOfOrderEpochsKeepExecutionOrderAndEvictNumericallyOldestSuper() {
        View view = new View();
        view.ready(CENTER);
        ExplosionLedger original = new ExplosionLedger();
        original.schedule(superParams(1), Level.OVERWORLD);
        original.schedule(superParams(2), Level.OVERWORLD);
        for (int i = 2; i < ExplosionLedger.MAX_ENTRIES; i++) original.schedule(bitmapParams(i), Level.OVERWORLD);
        CompoundTag saved = original.save(new CompoundTag(), null);
        ListTag savedList = saved.getList("entries", Tag.TAG_COMPOUND);
        savedList.getCompound(0).putLong("epoch", 2L);
        savedList.getCompound(1).putLong("epoch", 1L);
        ExplosionLedger restored = ExplosionLedger.load(saved, null);
        List<Double> order = new ArrayList<>();
        view.flush(restored, (l, p, c) -> {
            if (p.mode() == ExplosionParams.Mode.SUPER) order.add(p.radius());
            else throw new IllegalStateException("leave bitmap pending");
        });
        assertEquals(List.of(2.0, 1.0), order);
        assertTrue(view.schedule(restored, superParams(3)));
        ListTag after = savedEntries(restored);
        assertEquals(2L, after.getCompound(0).getLong("epoch"));
        assertEquals(1.0, after.getCompound(0).getDouble("r"));
        assertEquals("NORMAL", after.getCompound(1).getString("mode"));
        assertEquals(ExplosionLedger.MAX_ENTRIES + 1L, after.getCompound(after.size() - 1).getLong("epoch"));
    }

    @Test
    void allBitmapFullFallbackSharesBudgetAndNeverEnrollsFutureLoadedOrWaitingAtRejection() {
        View view = new View();
        for (int i = 1; i <= 70; i++) view.ready(new ChunkPos(i, 0));
        ChunkPos waiting = new ChunkPos(71, 0);
        view.waiting(waiting);
        ExplosionLedger ledger = bitmapFull();
        ExplosionParams rejected = superParams(100_000);
        assertFalse(view.schedule(ledger, rejected));
        ChunkPos future = new ChunkPos(72, 0);
        LevelChunk futureChunk = view.ready(future);
        view.ready(waiting);
        ledger.scheduleCheck(Level.OVERWORLD, future);
        ledger.scheduleCheck(Level.OVERWORLD, waiting);
        List<ChunkPos> applied = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> {
            assertSame(rejected, p);
            applied.add(c.getPos());
        });
        assertEquals(32, applied.size());
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(64, applied.size());
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(70, applied.size());
        assertFalse(applied.contains(future));
        assertFalse(applied.contains(waiting));
        assertEquals(0L, view.watermark(futureChunk));
        for (AtomicReference<Long> value : view.epochs.values()) assertNull(value.get(), "fallback never writes W");
        assertEquals(ExplosionLedger.MAX_ENTRIES, ledger.entryCount());
        assertEquals(ExplosionLedger.MAX_ENTRIES, savedEntries(ledger).size());
    }

    @Test
    void fallbackMergesOrderWithLaterPersistentSuperWithoutWatermarkSkippingEarlierJobs() {
        View view = new View();
        LevelChunk chunk = view.ready(CENTER);
        ExplosionLedger ledger = bitmapFull();
        ExplosionParams fallback1 = superParams(2);
        ExplosionParams fallback2 = superParams(3);
        assertFalse(view.schedule(ledger, fallback1));
        assertFalse(view.schedule(ledger, fallback2));
        List<ExplosionParams> first = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> {
            first.add(p);
            if (p == fallback1) throw new IllegalStateException("retry fallback");
        });
        assertEquals(ExplosionLedger.MAX_ENTRIES + 1, first.size());
        assertEquals(0, ledger.entryCount(), "bitmap completes despite fallback failure");
        assertEquals(0L, view.watermark(chunk));
        ExplosionParams persistent = superParams(4);
        view.schedule(ledger, persistent);
        List<ExplosionParams> order = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> {
            assertEquals(0L, view.watermark(c));
            order.add(p);
        });
        assertEquals(List.of(fallback1, fallback2, persistent), order);
        assertEquals(ExplosionLedger.MAX_ENTRIES + 3L, view.watermark(chunk));
        ledger.scheduleCheck(Level.OVERWORLD, CENTER);
        view.flush(ledger, (l, p, c) -> fail("completed fallback and persistent must not replay"));
    }

    @Test
    void fallbackTemporarilyWaitingKeepsOriginalHolderButReplacementAndUnloadPruneEligibility() {
        View view = new View();
        ChunkPos pause = new ChunkPos(1, 0);
        ChunkPos replaceHolder = new ChunkPos(2, 0);
        ChunkPos replaceChunk = new ChunkPos(3, 0);
        ChunkPos unload = new ChunkPos(4, 0);
        view.ready(pause);
        view.ready(replaceHolder);
        view.ready(replaceChunk);
        view.ready(unload);
        ExplosionLedger ledger = bitmapFull();
        assertFalse(view.schedule(ledger, superParams(1_000)));
        ExplosionLedger.VisibleChunk pausedState = view.lookup(pause);
        view.states.put(pause, new ExplosionLedger.VisibleChunk(pausedState.holder(), null));
        view.ready(replaceHolder);
        ExplosionLedger.VisibleChunk originalChunkState = view.lookup(replaceChunk);
        LevelChunk replacement = view.ready(new ChunkPos(8, 0));
        view.states.put(replaceChunk, new ExplosionLedger.VisibleChunk(originalChunkState.holder(), replacement));
        ledger.forgetChunk(Level.OVERWORLD, unload);
        List<ChunkPos> applied = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertTrue(applied.isEmpty());
        view.states.put(pause, pausedState);
        ledger.scheduleCheck(Level.OVERWORLD, unload);
        view.flush(ledger, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(List.of(pause), applied);
    }

    @Test
    void unloadDuringRoundPreventsWatermarkAndReentrantReplacementQueuesNewChunk() {
        View view = new View();
        LevelChunk oldChunk = view.ready(CENTER);
        ExplosionLedger ledger = new ExplosionLedger();
        ExplosionParams first = superParams(1);
        ExplosionParams second = superParams(2);
        view.schedule(ledger, first);
        view.schedule(ledger, second);
        List<ExplosionParams> order = new ArrayList<>();
        view.flush(ledger, (l, p, c) -> {
            order.add(p);
            ledger.forgetChunk(Level.OVERWORLD, CENTER);
            view.ready(CENTER);
            ledger.scheduleCheck(Level.OVERWORLD, CENTER);
        });
        assertEquals(List.of(first), order);
        assertEquals(0L, view.watermark(oldChunk));
        assertEquals(1, ledger.pendingCheckCount(Level.OVERWORLD));
        LevelChunk newChunk = view.lookup(CENTER).chunk();
        view.flush(ledger, (l, p, c) -> order.add(p));
        assertEquals(List.of(first, first, second), order);
        assertEquals(2L, view.watermark(newChunk));
    }

    @Test
    void runtimeCleanupDropsFallbackButBootstrapsPersistentEntries() {
        View view = new View();
        ChunkPos away = new ChunkPos(1, 0);
        view.ready(away);
        ExplosionLedger full = bitmapFull();
        assertFalse(view.schedule(full, superParams(1_000)));
        full.clearRuntimeState();
        view.flush(full, (l, p, c) -> fail("fallback must be cleared on runtime cleanup"));
        ExplosionLedger persistent = new ExplosionLedger();
        view.schedule(persistent, superParams(1_000));
        persistent.clearRuntimeState();
        List<ChunkPos> applied = new ArrayList<>();
        view.flush(persistent, (l, p, c) -> applied.add(c.getPos()));
        assertEquals(List.of(away), applied);
    }

    @Test
    void actualVisibleHolderLookupIsNonblockingAndIncludesNotFullHolders() {
        ChunkMap map = mock(ChunkMap.class);
        ChunkHolder waiting = mock(ChunkHolder.class);
        ChunkHolder ready = mock(ChunkHolder.class);
        ChunkHolder failed = mock(ChunkHolder.class);
        ChunkPos readyPos = new ChunkPos(1, 0);
        ChunkPos failedPos = new ChunkPos(2, 0);
        when(waiting.getPos()).thenReturn(CENTER);
        when(ready.getPos()).thenReturn(readyPos);
        when(failed.getPos()).thenReturn(failedPos);
        when(waiting.getFullChunkFuture()).thenReturn(new CompletableFuture<>());
        LevelChunk chunk = mock(LevelChunk.class);
        when(ready.getFullChunkFuture()).thenReturn(CompletableFuture.completedFuture(ChunkResult.of(chunk)));
        when(failed.getFullChunkFuture()).thenReturn(CompletableFuture.completedFuture(ChunkResult.error("not full")));
        when(map.getChunks()).thenReturn(List.of(waiting, ready, failed));
        when(map.getVisibleChunkIfPresent(CENTER.toLong())).thenReturn(waiting);
        when(map.getVisibleChunkIfPresent(readyPos.toLong())).thenReturn(ready);
        when(map.getVisibleChunkIfPresent(failedPos.toLong())).thenReturn(failed);
        ExplosionLedger.VisibleChunks view = ExplosionLedger.visibleChunks(map);
        List<ChunkPos> snapshot = new ArrayList<>();
        view.snapshot().forEach(snapshot::add);
        assertEquals(List.of(CENTER, readyPos, failedPos), snapshot);
        assertSame(waiting, view.lookup(CENTER).holder());
        assertNull(view.lookup(CENTER).chunk());
        assertSame(chunk, view.lookup(readyPos).chunk());
        assertSame(failed, view.lookup(failedPos).holder());
        assertNull(view.lookup(failedPos).chunk());
        assertNull(view.lookup(new ChunkPos(99, 99)).holder());
        verify(map, times(6)).getVisibleChunkIfPresent(anyLong());
        verify(map).getChunks();
        verifyNoMoreInteractions(map);
    }

    private static final class EpochHolder extends AttachmentHolder {
        void restore(HolderLookup.Provider registries, CompoundTag tag) {
            deserializeAttachments(registries, tag);
        }
    }

    @Test
    void registeredEpochAttachmentSerializesLongAndRestoresWithoutCreatingDefaultOnRead() {
        EpochHolder holder = new EpochHolder();
        assertNull(holder.getExistingDataOrNull(TntComponents.EXPLOSION_EPOCH));
        assertFalse(holder.hasAttachments());
        holder.setData(TntComponents.EXPLOSION_EPOCH, Long.MAX_VALUE - 1);
        HolderLookup.Provider registries = HolderLookup.Provider.create(java.util.stream.Stream.empty());
        CompoundTag saved = holder.serializeAttachments(registries);
        assertNotNull(saved);
        assertTrue(saved.contains("living_item:tnt_explosion_epoch", Tag.TAG_LONG));
        EpochHolder restored = new EpochHolder();
        restored.restore(registries, saved);
        assertEquals(Long.MAX_VALUE - 1, restored.getExistingDataOrNull(TntComponents.EXPLOSION_EPOCH));
    }
}
