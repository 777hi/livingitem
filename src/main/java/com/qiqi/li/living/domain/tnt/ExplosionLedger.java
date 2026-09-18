package com.qiqi.li.living.domain.tnt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.qiqi.li.LivingItem;
import com.qiqi.li.logging.ModLog;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 待炸账本 —— 「未观测的地形变更推迟到观测时」（原版 TNT 引信模型的**爆炸范围**版本）。
 *
 * <p>背景与完整推导见 {@code docs/system-design/living-item-infrastructure.md} §3.2.2。</p>
 *
 * <h3>它解决什么</h3>
 * 爆炸半径可达 235 格（跨几十上百个区块），其中一部分可能未加载。若在爆炸瞬间去读那些区块，
 * 就会走 {@code getChunk(requireChunk=true)} ⇒ 强制加载（单次 289 区块足迹、主线程阻塞、
 * 票据钉住）。若直接跳过，爆炸语义就残缺。
 *
 * <p><b>原版给的答案</b>：原版 TNT 的引信在未 tick 的区块里**冻结**，玩家回来才续走爆炸
 * （{@code PrimedTnt.fuse} 随区块 NBT 持久化，实体只在 ticking 区块 tick）。即
 * 「世界只在被观测的地方演化」。本账本把这条原则应用到**爆炸范围**上：</p>
 * <ul>
 *   <li>爆炸时只登记参数（{@link ExplosionParams}）+ 位图，**不读任何方块**</li>
 *   <li>区块**已加载** → 在 tick 阶段按预算分帧应用破坏</li>
 *   <li>区块**未加载** → 什么都不做；它将来**自然加载**时（{@code ChunkEvent.Load}）登记，
 *       下一 tick 应用 ⇒ 玩家走过去看到的是"已经炸过"的地形</li>
 * </ul>
 *
 * <h3>两条红线（与 ContainerChunkCache 同款，别违反）</h3>
 * <ol>
 *   <li>{@link #onChunkLoaded} 在区块加载任务**内部**被回调 ⇒ <b>只登记坐标，不碰世界</b>
 *       （见 {@code ContainerChunkCache.onChunkLoad} 的说明）。</li>
 *   <li>{@link #flush} 是唯一允许读方块/改方块的地方 —— 它在 {@code ServerTickEvent.Pre} 里被调用，
 *       主线程不在任何区块任务内部。</li>
 * </ol>
 */
public class ExplosionLedger extends SavedData {

    /** 存档内的数据名。 */
    private static final String DATA_ID = "living_item_pending_explosions";

    /** 待炸条目上限 —— 防御异常累积（每条最大约 130 字节位图）。 */
    static final int MAX_ENTRIES = 256;

    /** 单 tick 最多应用多少个区块（分帧预算）—— 见 §3.2.2「限速」安全阀。 */
    static final int MAX_CHUNKS_PER_TICK = 32;

    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_X = "cx";
    private static final String KEY_Y = "cy";
    private static final String KEY_Z = "cz";
    private static final String KEY_RADIUS = "r";
    private static final String KEY_MODE = "mode";
    private static final String KEY_DROPS = "drops";
    private static final String KEY_DONE = "done";

    private static final SavedData.Factory<ExplosionLedger> FACTORY =
        new SavedData.Factory<>(ExplosionLedger::new, ExplosionLedger::load);

    /** 实际破坏的执行者 —— 抽成参数是为了让记账逻辑可以脱离世界单测。 */
    @FunctionalInterface
    public interface ChunkApplier {
        void apply(ServerLevel level, ExplosionParams params, LevelChunk chunk);
    }

    /** 待炸条目（随存档持久化）。 */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * 待检查区块队列（按维度）—— **只写内存，不持久化**。
     *
     * <p>它只是"下一 tick 去看一眼"的调度表，丢了会自动补上：区块已在内存的条目，
     * 每 tick 的处理预算会继续推进（见 {@link #flush} 的 carryOver）；区块未加载的，
     * 将来加载时会再登记一次。</p>
     *
     * <p>⚠️ <b>有界性不变量</b>：队列规模 ≈ <b>已加载区块数</b>（几百），不会无界膨胀 ——
     * 因为 {@link #flush} 每 pass 都把**未加载**的区块**丢弃**掉。**这个"丢弃"不是偷懒，
     * 是队列有界的前提**：若改成"保留未加载的以便重试"，队列会随"玩家在未探索区连续引爆"
     * 无界增长，每 tick 重扫几十万个区块 ⇒ 服务端冻结。改动前先想清楚。</p>
     */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> pendingChecks = new Object2ObjectOpenHashMap<>();

    public ExplosionLedger() {}

    /** 取（或创建）该维度的账本。 */
    public static ExplosionLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    // ═══════════════════════════════════════════════════════════
    //  条目
    // ═══════════════════════════════════════════════════════════

    /** 一条待炸记录：参数 + 完成位图。 */
    static final class Entry {
        final ExplosionParams params;
        final long[] done;
        int remaining;

        Entry(ExplosionParams params) {
            this.params = params;
            this.done = new long[(params.chunkCount() + 63) >> 6];
            this.remaining = params.chunkCount();
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

    // ═══════════════════════════════════════════════════════════
    //  建档（爆炸瞬间）
    // ═══════════════════════════════════════════════════════════

    /**
     * 登记一次爆炸。**不读任何方块** —— 只做几何计算与记账。
     *
     * @return 是否登记成功（超出上限时返回 false）
     */
    public static boolean schedule(ServerLevel level, ExplosionParams params) {
        return get(level).schedule(params, level.dimension());
    }

    /** 核心记账逻辑（实例级，便于单测直接 new 出来验证）。 */
    boolean schedule(ExplosionParams params, ResourceKey<Level> dim) {
        if (entries.size() >= MAX_ENTRIES) {
            ModLog.CONTAINER.warn("待炸账本已满（{} 条），本次爆炸（中心 {} {} {} 半径 {}）不再补齐未加载区块",
                MAX_ENTRIES, params.centerX(), params.centerY(), params.centerZ(), params.radius());
            return false;
        }

        Entry entry = new Entry(params);
        // 圆形作用范围之外的区块不需要处理 → 直接标记完成（位图仍占位，保证索引稳定）
        for (int bit = 0; bit < params.chunkCount(); bit++) {
            if (!params.affects(params.chunkAt(bit))) entry.markDone(bit);
        }
        entries.add(entry);
        setDirty();

        // 登记所有"还没完成"的区块 → 下一 tick 起按预算推进
        for (int bit = 0; bit < params.chunkCount(); bit++) {
            if (!entry.isDone(bit)) scheduleCheck(dim, params.chunkAt(bit));
        }
        return true;
    }

    /** 登记一个待检查区块（包级可见：既是 {@code ChunkEvent.Load} 的落点，也是单测的 seam）。 */
    void scheduleCheck(ResourceKey<Level> dim, ChunkPos pos) {
        pendingChecks.computeIfAbsent(dim, k -> new ObjectOpenHashSet<>()).add(pos);
    }

    // ═══════════════════════════════════════════════════════════
    //  事件：区块加载（红线 —— 只登记，不碰世界）
    // ═══════════════════════════════════════════════════════════

    /**
     * 区块自然加载时登记 —— <b>唯一允许的调用是纯 getter</b>。
     *
     * <p>与 {@code ContainerChunkCache.onChunkLoad} 同款红线：本方法在区块 FULL 任务**内部**
     * 被回调，做任何世界交互都可能触发同步区块加载 ⇒ 主线程自等自。真正的破坏交给
     * {@link #flush}（tick 阶段）。</p>
     */
    public static void onChunkLoaded(ServerLevel level, ChunkPos pos) {
        ExplosionLedger ledger = get(level);
        if (ledger.entries.isEmpty()) return; // 绝大多数情况下账本是空的，连登记都省了
        ledger.scheduleCheck(level.dimension(), pos);
    }

    // ═══════════════════════════════════════════════════════════
    //  执行（tick 阶段 —— 唯一允许读/改方块的地方）
    // ═══════════════════════════════════════════════════════════

    /** 遍历所有维度推进（由 {@code ServerTickEvent.Pre} 调用）。 */
    public static void flushAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            get(level).flush(level);
        }
    }

    /** 推进本维度（生产路径：实际破坏走 {@link ExplosionComponent#applyToChunk}）。 */
    public void flush(ServerLevel level) {
        flush(level, ExplosionComponent::applyToChunk);
    }

    /**
     * 推进本维度：把"已加载且未完成"的区块应用掉，每 tick 限量 {@value #MAX_CHUNKS_PER_TICK} 个。
     *
     * <p>三种出队情形要分清（写错会丢爆炸）：</p>
     * <ul>
     *   <li>区块**未加载**（{@code getChunkNow} 为 null）→ <b>丢弃</b>，等它将来加载时由
     *       {@link #onChunkLoaded} 重新登记。**不能留着轮询**（否则每 tick 扫几百个未加载区块）。</li>
     *   <li>**预算用尽** → <b>留到下一 tick</b>（carryOver）。这些区块已经加载了，不会再触发
     *       {@code ChunkEvent.Load}，丢了就永远不炸。</li>
     *   <li>条目全部完成 → 移除（账本自动收敛）。</li>
     * </ul>
     *
     * <p>⚠️ <b>多场爆炸叠加时不变量</b>：一个区块可能落在多条条目的范围内 ⇒ 会对每条条目各处理一次
     * （破坏是**幂等**的：第二次看到的已是空气）。队列只移除"本 tick 处理过的"区块，
     * <b>不整体替换</b> —— 否则同 tick 新登记的区块会被覆盖丢失（见方法内注释）。</p>
     *
     * @param applier 实际破坏的执行者（生产 = {@code ExplosionComponent::applyToChunk}）
     */
    void flush(ServerLevel level, ChunkApplier applier) {
        Set<ChunkPos> pending = pendingChecks.get(level.dimension());
        if (pending == null || pending.isEmpty()) return;

        // 遍历**快照**：applier 若间接引发新的登记（同 tick 内又引爆一场），不能边遍历边改集合。
        List<ChunkPos> snapshot = new ArrayList<>(pending);
        int budget = MAX_CHUNKS_PER_TICK;
        boolean dirty = false;

        for (ChunkPos cp : snapshot) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) {
                pending.remove(cp); // 未加载 → 丢弃，等自然加载时重新登记（见 javadoc）
                continue;
            }
            if (budget <= 0) {
                continue; // 预算用尽 → **留在队列里**（carryOver），下一 tick 继续
            }
            budget--;

            for (Entry entry : List.copyOf(entries)) {
                int bit = entry.params.bitIndexOf(cp);
                if (bit < 0 || entry.isDone(bit) || !entry.params.affects(cp)) continue;
                applier.apply(level, entry.params, chunk);
                entry.markDone(bit);
                dirty = true;
            }
            pending.remove(cp); // 已处理 → 出队
        }

        // ⚠️ 只移除**本 tick 处理过的**区块，绝不整体替换/清空队列 —— 否则本 tick 期间新登记的
        // 区块（同 tick 又引爆了一场）会被一起丢掉；而它们已经加载、不会再触发 ChunkEvent.Load，
        // 丢了就永远不炸。
        if (pending.isEmpty()) {
            pendingChecks.remove(level.dimension());
        }

        int before = entries.size();
        entries.removeIf(e -> e.remaining <= 0);
        if (entries.size() != before) {
            dirty = true;
            ModLog.CONTAINER.info("待炸账本: {} 条爆炸已补齐完成（剩余 {} 条）", before - entries.size(), entries.size());
        }
        if (dirty) setDirty();
    }

    // ═══════════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════════

    /**
     * 清空**所有维度**的内存调度表（服务端关闭时调用）。
     *
     * <p>条目本身随存档保存、不需要清；但 {@link #pendingChecks} 是静态可及的内存状态，
     * 单人游戏「退出存档 → 进另一个存档」不重启 JVM ⇒ 不清会把上一个维度的调度表带过来。
     * 这正是旧 {@code PENDING_SUPER_EXPLOSIONS} 漏做的清理。</p>
     */
    public static void clearAllRuntimeState(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            get(level).pendingChecks.clear();
        }
    }

    /** 供调试与测试：待炸条目数。 */
    public int entryCount() {
        return entries.size();
    }

    /** 供调试与测试：待检查区块数（指定维度）。 */
    public int pendingCheckCount(ResourceKey<Level> dim) {
        Set<ChunkPos> set = pendingChecks.get(dim);
        return set == null ? 0 : set.size();
    }

    /** 供调试与测试：某条爆炸的剩余区块数。 */
    int remainingOf(int index) {
        return entries.get(index).remaining;
    }

    /** 供调试与测试：所有条目里尚未完成、且在圆形作用范围内的区块。 */
    List<ChunkPos> remainingChunks() {
        List<ChunkPos> out = new ArrayList<>();
        for (Entry e : entries) {
            for (int bit = 0; bit < e.params.chunkCount(); bit++) {
                if (e.isDone(bit)) continue;
                ChunkPos cp = e.params.chunkAt(bit);
                if (e.params.affects(cp)) out.add(cp);
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════
    //  持久化
    // ═══════════════════════════════════════════════════════════

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.putDouble(KEY_X, e.params.centerX());
            t.putDouble(KEY_Y, e.params.centerY());
            t.putDouble(KEY_Z, e.params.centerZ());
            t.putDouble(KEY_RADIUS, e.params.radius());
            t.putString(KEY_MODE, e.params.mode().name());
            t.putBoolean(KEY_DROPS, e.params.vanillaDrops());
            t.putLongArray(KEY_DONE, e.done);
            list.add(t);
        }
        tag.put(KEY_ENTRIES, list);
        return tag;
    }

    /** 反序列化（包级可见：单测直接验证往返）。 */
    static ExplosionLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        ExplosionLedger ledger = new ExplosionLedger();
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            ExplosionParams.Mode mode;
            try {
                mode = ExplosionParams.Mode.valueOf(t.getString(KEY_MODE));
            } catch (IllegalArgumentException ex) {
                LivingItem.LOGGER.warn("待炸账本: 未知模式 '{}'，跳过该条", t.getString(KEY_MODE));
                continue;
            }
            ExplosionParams params = new ExplosionParams(
                t.getDouble(KEY_X), t.getDouble(KEY_Y), t.getDouble(KEY_Z),
                t.getDouble(KEY_RADIUS), mode, t.getBoolean(KEY_DROPS));

            Entry entry = new Entry(params);
            long[] saved = t.getLongArray(KEY_DONE);
            System.arraycopy(saved, 0, entry.done, 0, Math.min(saved.length, entry.done.length));
            // 重算剩余数：位图是唯一真相，别信存档里的计数
            entry.remaining = 0;
            for (int bit = 0; bit < params.chunkCount(); bit++) {
                if (!entry.isDone(bit)) entry.remaining++;
            }
            if (entry.remaining > 0) ledger.entries.add(entry);
        }
        return ledger;
    }
}
