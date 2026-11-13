package com.qiqi.li.living.domain.tnt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.qiqi.li.logging.ModLog;
import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;

/** 待炸账本：普通模式保存完成位图，SUPER 保存参数与纪元；破坏只在 tick 内按区块预算执行。 */
public class ExplosionLedger extends SavedData {
    private static final String DATA_ID = "living_item_pending_explosions";
    static final int MAX_ENTRIES = 4096;
    static final int MAX_CHUNKS_PER_TICK = 32;

    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_LAST_EPOCH = "lastEpoch";
    private static final String KEY_EPOCH = "epoch";
    private static final String KEY_X = "cx";
    private static final String KEY_Y = "cy";
    private static final String KEY_Z = "cz";
    private static final String KEY_RADIUS = "r";
    private static final String KEY_MODE = "mode";
    private static final String KEY_DROPS = "drops";
    private static final String KEY_DONE = "done";
    private static final SavedData.Factory<ExplosionLedger> FACTORY =
        new SavedData.Factory<>(ExplosionLedger::new, ExplosionLedger::load);

    static {
        StaticCacheRegistry.onServerStop(ExplosionLedger::clearAllRuntimeState);
    }

    @FunctionalInterface
    public interface ChunkApplier {
        void apply(ServerLevel level, ExplosionParams params, LevelChunk chunk);
    }

    /** 仅观察可见 holder；null holder = 未加载，null chunk = FULL 尚未就绪。 */
    record VisibleChunk(Object holder, LevelChunk chunk) {}

    interface VisibleChunks {
        Iterable<ChunkPos> snapshot();
        VisibleChunk lookup(ChunkPos pos);
    }

    static VisibleChunks visibleChunks(ServerLevel level) {
        return visibleChunks(level.getChunkSource() == null ? null : level.getChunkSource().chunkMap);
    }

    static VisibleChunks visibleChunks(ChunkMap map) {
        return new VisibleChunks() {
            @Override
            public Iterable<ChunkPos> snapshot() {
                List<ChunkPos> positions = new ArrayList<>();
                if (map != null) {
                    for (ChunkHolder holder : map.getChunks()) positions.add(holder.getPos());
                }
                return positions;
            }

            @Override
            public VisibleChunk lookup(ChunkPos pos) {
                if (map == null) return new VisibleChunk(null, null);
                ChunkHolder holder = map.getVisibleChunkIfPresent(pos.toLong());
                if (holder == null) return new VisibleChunk(null, null);
                ChunkResult<LevelChunk> result = holder.getFullChunkFuture().getNow(null);
                return new VisibleChunk(holder, result == null ? null : result.orElse(null));
            }
        };
    }

    private abstract static class Entry {
        final ExplosionParams params;
        final long epoch;

        Entry(ExplosionParams params, long epoch) {
            this.params = params;
            this.epoch = epoch;
        }
    }

    private static final class BitmapEntry extends Entry {
        final long[] done;
        int remaining;

        BitmapEntry(ExplosionParams params, long epoch) {
            super(params, epoch);
            params.validateBitmapLayout();
            int count = params.chunkCount();
            done = new long[(int) (((long) count + 63L) >> 6)];
            remaining = count;
        }

        boolean isDone(int bit) {
            return (done[bit >> 6] & (1L << (bit & 63))) != 0;
        }

        void markDone(int bit) {
            if (isDone(bit)) return;
            done[bit >> 6] |= 1L << (bit & 63);
            remaining--;
        }
    }

    private static final class SuperEntry extends Entry {
        SuperEntry(ExplosionParams params, long epoch) {
            super(params, epoch);
        }
    }

    /** 拒绝的 SUPER 仅作用于建档时 READY 的同一 holder/chunk；每个 job 有限，不另设 job 数量上限。 */
    private static final class FallbackJob extends Entry {
        final Map<ChunkPos, VisibleChunk> eligible = new LinkedHashMap<>();

        FallbackJob(ExplosionParams params, long epoch) {
            super(params, epoch);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private long lastEpoch;
    private final Map<ResourceKey<Level>, LinkedHashSet<ChunkPos>> pendingChecks = new LinkedHashMap<>();
    private final List<FallbackJob> fallbackJobs = new ArrayList<>();
    private boolean bootstrapRequired;
    private ResourceKey<Level> activeDimension;
    private ChunkPos activePosition;
    private boolean activeUnloaded;

    public ExplosionLedger() {}

    public static ExplosionLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /** 返回 false 的 SUPER 已排入仅已加载部分的预算队列，调用方不能再同步执行。 */
    public static boolean schedule(ServerLevel level, ExplosionParams params) {
        return get(level).schedule(params, level.dimension(), visibleChunks(level));
    }

    boolean schedule(ExplosionParams params, ResourceKey<Level> dim) {
        return schedule(params, dim, null);
    }

    boolean schedule(ExplosionParams params, ResourceKey<Level> dim, VisibleChunks visible) {
        boolean isSuper = params.mode() == ExplosionParams.Mode.SUPER;
        if (!isSuper) params.validateBitmapLayout();
        if (entries.size() >= MAX_ENTRIES && !isSuper) {
            warnFull(params);
            return false;
        }
        long epoch = Math.incrementExact(lastEpoch);
        Entry entry = isSuper ? new SuperEntry(params, epoch) : new BitmapEntry(params, epoch);
        lastEpoch = epoch;
        setDirty();
        if (entries.size() >= MAX_ENTRIES && !evictOldestSuper()) {
            warnFull(params);
            FallbackJob job = new FallbackJob(params, epoch);
            if (visible != null) {
                for (ChunkPos cp : visible.snapshot()) {
                    if (!params.affects(cp)) continue;
                    VisibleChunk state = visible.lookup(cp);
                    if (state.holder() != null && state.chunk() != null) {
                        job.eligible.put(cp, state);
                        scheduleCheck(dim, cp);
                    }
                }
            }
            if (!job.eligible.isEmpty()) fallbackJobs.add(job);
            return false;
        }
        if (entry instanceof BitmapEntry bitmap) {
            for (int bit = 0; bit < params.chunkCount(); bit++) {
                ChunkPos cp = params.chunkAt(bit);
                if (!params.affects(cp)) bitmap.markDone(bit);
                else if (visible == null) scheduleCheck(dim, cp);
            }
        }
        entries.add(entry);
        if (visible != null) {
            for (ChunkPos cp : visible.snapshot()) {
                if (params.affects(cp)) scheduleCheck(dim, cp);
            }
        }
        return true;
    }

    private boolean evictOldestSuper() {
        int oldest = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof SuperEntry &&
                (oldest < 0 || entries.get(i).epoch < entries.get(oldest).epoch)) oldest = i;
        }
        if (oldest < 0) return false;
        Entry entry = entries.remove(oldest);
        setDirty();
        ModLog.CONTAINER.warn("待炸账本已满（{} 条），逐出最旧 SUPER 纪元 {}，其未完成部分不再补齐",
            MAX_ENTRIES, entry.epoch);
        return true;
    }

    private static void warnFull(ExplosionParams params) {
        ModLog.CONTAINER.warn("待炸账本已满（{} 条），本次爆炸（中心 {} {} {} 半径 {}）仅处理已加载部分",
            MAX_ENTRIES, params.centerX(), params.centerY(), params.centerZ(), params.radius());
    }

    void scheduleCheck(ResourceKey<Level> dim, ChunkPos pos) {
        pendingChecks.computeIfAbsent(dim, k -> new LinkedHashSet<>()).add(pos);
    }

    /** 加载任务内部只登记坐标，不查询 FULL 或操作世界。 */
    public static void onChunkLoaded(ServerLevel level, ChunkPos pos) {
        ExplosionLedger ledger = get(level);
        if (!ledger.entries.isEmpty()) ledger.scheduleCheck(level.dimension(), pos);
    }

    public static void onChunkUnloaded(ServerLevel level, ChunkPos pos) {
        get(level).forgetChunk(level.dimension(), pos);
    }

    void forgetChunk(ResourceKey<Level> dim, ChunkPos pos) {
        LinkedHashSet<ChunkPos> pending = pendingChecks.get(dim);
        if (pending != null) pending.remove(pos);
        for (FallbackJob job : fallbackJobs) job.eligible.remove(pos);
        fallbackJobs.removeIf(job -> job.eligible.isEmpty());
        if (dim.equals(activeDimension) && pos.equals(activePosition)) activeUnloaded = true;
    }

    public static void flushAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) get(level).flush(level);
    }

    public void flush(ServerLevel level) {
        flush(level, ExplosionComponent::applyToChunk);
    }

    void flush(ServerLevel level, ChunkApplier applier) {
        flush(level, applier, visibleChunks(level));
    }

    void flush(ServerLevel level, ChunkApplier applier, VisibleChunks visible) {
        ResourceKey<Level> dim = level.dimension();
        if (bootstrapRequired) {
            bootstrapRequired = false;
            for (ChunkPos cp : visible.snapshot()) {
                if (hasPersistentWork(cp)) scheduleCheck(dim, cp);
            }
        }
        LinkedHashSet<ChunkPos> pending = pendingChecks.get(dim);
        if (pending == null || pending.isEmpty()) return;
        List<ChunkPos> snapshot = new ArrayList<>(pending);
        int budget = MAX_CHUNKS_PER_TICK;
        for (ChunkPos cp : snapshot) {
            if (budget == 0) break;
            // 出队先于执行；执行期间同坐标的新登记必须留到下一 tick。
            pending.remove(cp);
            activeDimension = dim;
            activePosition = cp;
            activeUnloaded = false;
            try {
                VisibleChunk state = visible.lookup(cp);
                if (state.holder() == null) {
                    forgetChunk(dim, cp);
                    continue;
                }
                pruneFallback(cp, state);
                if (state.chunk() == null) {
                    pending.add(cp);
                    continue;
                }
                budget--;
                if (!applyRound(level, cp, state, applier, visible) && !activeUnloaded) pending.add(cp);
            } catch (RuntimeException ex) {
                if (!activeUnloaded) pending.add(cp);
                ModLog.CONTAINER.warn("待炸账本: 区块 {} 应用失败，保留本轮重试", cp, ex);
            } finally {
                activeDimension = null;
                activePosition = null;
                activeUnloaded = false;
            }
        }
        if (pending.isEmpty()) pendingChecks.remove(dim);
        if (entries.removeIf(entry -> entry instanceof BitmapEntry bitmap && bitmap.remaining <= 0)) setDirty();
        fallbackJobs.removeIf(job -> job.eligible.isEmpty());
    }

    private boolean applyRound(ServerLevel level, ChunkPos cp, VisibleChunk state,
                               ChunkApplier applier, VisibleChunks visible) {
        LevelChunk chunk = state.chunk();
        Long savedEpoch = chunk.getExistingDataOrNull(TntComponents.EXPLOSION_EPOCH);
        long oldW = savedEpoch == null ? 0L : savedEpoch;
        long newW = oldW;
        List<Entry> round = new ArrayList<>(entries);
        round.addAll(fallbackJobs);
        round.sort(Comparator.comparingLong(entry -> entry.epoch));
        for (Entry entry : round) {
            if (activeUnloaded || !sameReady(state, visible.lookup(cp))) return false;
            if (entry instanceof BitmapEntry bitmap) {
                int bit = entry.params.bitIndexOf(cp);
                if (bit < 0 || bitmap.isDone(bit) || !entry.params.affects(cp)) continue;
                applier.apply(level, entry.params, chunk);
                bitmap.markDone(bit);
                setDirty();
            } else if (entry instanceof FallbackJob job) {
                VisibleChunk eligible = job.eligible.get(cp);
                if (eligible == null || !sameReady(eligible, state)) continue;
                applier.apply(level, job.params, chunk);
                job.eligible.remove(cp);
            } else if (entry.epoch > oldW && entry.params.affects(cp)) {
                applier.apply(level, entry.params, chunk);
                newW = Math.max(newW, entry.epoch);
            }
        }
        // 水印只覆盖快照内成功完成的持久 SUPER；降级 job 不读写水印。
        if (activeUnloaded || !sameReady(state, visible.lookup(cp))) return false;
        if (newW > oldW) chunk.setData(TntComponents.EXPLOSION_EPOCH, newW);
        return true;
    }

    private static boolean sameReady(VisibleChunk expected, VisibleChunk actual) {
        return expected.holder() == actual.holder() && expected.chunk() != null && expected.chunk() == actual.chunk();
    }

    private void pruneFallback(ChunkPos cp, VisibleChunk state) {
        for (FallbackJob job : fallbackJobs) {
            VisibleChunk eligible = job.eligible.get(cp);
            if (eligible != null && (eligible.holder() != state.holder() ||
                (state.chunk() != null && eligible.chunk() != state.chunk()))) job.eligible.remove(cp);
        }
    }

    private boolean hasPersistentWork(ChunkPos cp) {
        for (Entry entry : entries) {
            if (entry instanceof BitmapEntry bitmap) {
                int bit = entry.params.bitIndexOf(cp);
                if (bit >= 0 && !bitmap.isDone(bit) && entry.params.affects(cp)) return true;
            } else if (entry.params.affects(cp)) return true;
        }
        return false;
    }

    public static void clearAllRuntimeState(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) get(level).clearRuntimeState();
    }

    void clearRuntimeState() {
        pendingChecks.clear();
        fallbackJobs.clear();
        bootstrapRequired = true;
        activeDimension = null;
        activePosition = null;
        activeUnloaded = false;
    }

    public int entryCount() {
        return entries.size();
    }

    public int pendingCheckCount(ResourceKey<Level> dim) {
        LinkedHashSet<ChunkPos> pending = pendingChecks.get(dim);
        return pending == null ? 0 : pending.size();
    }

    int remainingOf(int index) {
        return entries.get(index) instanceof BitmapEntry bitmap ? bitmap.remaining : -1;
    }

    /** 仅位图族有剩余网格；SUPER 不枚举覆盖面积。 */
    List<ChunkPos> remainingChunks() {
        List<ChunkPos> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (!(entry instanceof BitmapEntry bitmap)) continue;
            for (int bit = 0; bit < entry.params.chunkCount(); bit++) {
                if (bitmap.isDone(bit)) continue;
                ChunkPos cp = entry.params.chunkAt(bit);
                if (entry.params.affects(cp)) out.add(cp);
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag t = new CompoundTag();
            t.putDouble(KEY_X, entry.params.centerX());
            t.putDouble(KEY_Y, entry.params.centerY());
            t.putDouble(KEY_Z, entry.params.centerZ());
            t.putDouble(KEY_RADIUS, entry.params.radius());
            t.putString(KEY_MODE, entry.params.mode().name());
            t.putBoolean(KEY_DROPS, entry.params.vanillaDrops());
            t.putLong(KEY_EPOCH, entry.epoch);
            if (entry instanceof BitmapEntry bitmap) t.putLongArray(KEY_DONE, bitmap.done);
            list.add(t);
        }
        tag.putLong(KEY_LAST_EPOCH, lastEpoch);
        tag.put(KEY_ENTRIES, list);
        return tag;
    }

    static ExplosionLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        ExplosionLedger ledger = new ExplosionLedger();
        ledger.lastEpoch = Math.max(0L, tag.getLong(KEY_LAST_EPOCH));
        ledger.bootstrapRequired = true;
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            try {
                ExplosionParams.Mode mode = ExplosionParams.Mode.valueOf(t.getString(KEY_MODE));
                ExplosionParams params = new ExplosionParams(t.getDouble(KEY_X), t.getDouble(KEY_Y),
                    t.getDouble(KEY_Z), t.getDouble(KEY_RADIUS), mode, t.getBoolean(KEY_DROPS));
                long epoch = t.getLong(KEY_EPOCH);
                if (epoch <= 0) throw new IllegalArgumentException("nonpositive epoch");
                ledger.lastEpoch = Math.max(ledger.lastEpoch, epoch);
                if (ledger.entries.size() >= MAX_ENTRIES &&
                    (mode != ExplosionParams.Mode.SUPER || !ledger.evictOldestSuper())) {
                    warnFull(params);
                    continue;
                }
                Entry entry;
                if (mode == ExplosionParams.Mode.SUPER) {
                    entry = new SuperEntry(params, epoch);
                } else {
                    BitmapEntry bitmap = new BitmapEntry(params, epoch);
                    long[] saved = t.getLongArray(KEY_DONE);
                    System.arraycopy(saved, 0, bitmap.done, 0, Math.min(saved.length, bitmap.done.length));
                    for (int bit = 0; bit < params.chunkCount(); bit++) {
                        if (!params.affects(params.chunkAt(bit))) bitmap.markDone(bit);
                    }
                    bitmap.remaining = 0;
                    for (int bit = 0; bit < params.chunkCount(); bit++) {
                        if (!bitmap.isDone(bit)) bitmap.remaining++;
                    }
                    if (bitmap.remaining <= 0) continue;
                    entry = bitmap;
                }
                ledger.entries.add(entry);
            } catch (IllegalArgumentException | ArithmeticException ex) {
                ModLog.CONTAINER.warn("待炸账本: 无效条目 {}，跳过", i, ex);
            }
        }
        return ledger;
    }
}
