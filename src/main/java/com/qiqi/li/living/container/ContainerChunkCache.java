package com.qiqi.li.living.container;

import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.logging.ModLog;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 容器区块缓存 —— 维护"包含容器方块实体的区块"列表，避免每 tick 全量扫描所有区块。
 *
 * 为什么需要这个缓存：
 *   活物品 tick 需要遍历世界中所有容器来处理活物品逻辑。
 *   如果每 tick 都扫描所有已加载区块的所有方块实体，性能开销极大。
 *   实际上大部分区块不包含任何容器，所以只需缓存"有容器的区块"即可。
 *
 * 缓存维护策略（事件驱动）：
 *   - 区块加载 → **只登记坐标**，下一 tick 扫描该区块，若有容器则加入缓存
 *   - 区块卸载 → 从缓存中移除
 *   - 方块放置/破坏 → 下一 tick 重新扫描该区块，更新缓存
 *
 * 使用方式：
 *   在 {@link com.qiqi.li.LivingItem#onServerTick} 中，
 *   通过 {@link #getProcessableChunks} 获取当前维度的可处理容器区块列表，
 *   只遍历这些区块中的方块实体。
 *
 * <p>⚠️ <b>本类最重要的红线</b>：{@link #onChunkLoad} 在区块加载任务内部被回调，
 * 那里<b>只能写内存、不能碰世界</b>。所有能力查询（{@code getCapability}）必须推迟到
 * {@link #flushPendingRescans}（tick 阶段）执行。违反会导致服务端线程自等自的死锁 ——
 * 详见 {@link #onChunkLoad} 的说明。</p>
 *
 * <p>⚠️ <b>第二条红线</b>：<b>处理</b>（读能力 / 读邻居）只能针对 {@code ticking} 区区块，
 * 见 {@link #getProcessableChunks} —— 否则会触发跨区块强制加载。</p>
 */
public class ContainerChunkCache {
    private static final ContainerChunkCache INSTANCE = new ContainerChunkCache();

    /** 按维度存储包含容器的区块坐标集合 */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> chunkCache = new Object2ObjectOpenHashMap<>();

    /** 每个维度上次清理的时间戳（gameTime），用于定期清理已卸载的区块 */
    private final Map<ResourceKey<Level>, Long> lastCleanupTick = new Object2ObjectOpenHashMap<>();

    /**
     * 待重扫区块队列（按维度）。
     *
     * <p>方块放置/破坏事件触发时，目标位置的 BlockEntity 可能尚未完成初始化
     * （不少模组容器的 ItemHandler 依赖 BE 内部字段，在 {@code onLoad()} 或读 NBT 后才可用），
     * 此刻查询 capability 会返回 null，导致重扫扫不到刚放下的容器。
     * 因此把重扫推迟到下一 tick，由 {@link #flushPendingRescans} 执行。</p>
     *
     * <p>区块加载事件走同一条队列 —— 原因不是"BE 没就绪"，而是<b>事件回调的时机不安全</b>，
     * 见 {@link #onChunkLoad}。</p>
     */
    private final Map<ResourceKey<Level>, Set<ChunkPos>> pendingRescans = new Object2ObjectOpenHashMap<>();

    /**
     * 单 tick 最多执行的延后重扫数（见 {@link #flushPendingRescans}）。
     *
     * <p>包级可见是为了让回归测试能引用它，而不必在断言里硬编码魔法数字。</p>
     */
    static final int MAX_RESCANS_PER_TICK = 64;

    private ContainerChunkCache() {}

    /** 获取单例实例 */
    public static ContainerChunkCache getInstance() {
        return INSTANCE;
    }

    /**
     * 区块加载事件 —— 只登记"下一 tick 重扫"，<b>绝不在事件里碰世界</b>。
     *
     * <p>⚠️ <b>红线（2026-09-18，Create 跨区块传送带服务端死锁）</b></p>
     *
     * <p>本事件在区块 FULL 任务的主线程回调里触发
     * （{@code ChunkStatusTasks.full()} → {@code NeoForge.EVENT_BUS.post(new ChunkEvent.Load(...))}），
     * 此刻主线程正处在"区块加载"内部，且该区块<b>尚未保证</b>提升到 {@link net.minecraft.world.level.chunk.status.ChunkStatus#FULL}。
     * 在事件里做任何世界交互（能力查询 / 方块实体查找 / 读方块状态）都可能触发
     * <b>其他模组的同步区块加载</b>：{@code ServerChunkCache.getChunk(..., requireChunk=true)}
     * 会 {@code mainThreadProcessor.managedBlock(future::isDone)} + {@code future.join()}，
     * 去等一个"只能由主线程自己推进"的 chunk future ⇒ <b>自己等自己，服务端线程冻结</b>。</p>
     *
     * <p>NeoForge 在 {@code ChunkEvent.Load} 的 javadoc 里已明确警告：
     * "You will cause chunk loading deadlocks if you don't delay your level interactions."
     * 原版为此在 {@code ServerChunkCache.getChunk}/{@code getChunkNow} 里加了
     * {@code ChunkHolder.currentlyLoading} 旁路，但它<b>只覆盖"正在加载的那个区块本身"</b> ——
     * 于是"方块在同一个区块内"侥幸不卡，"方块指向另一个区块"必卡。</p>
     *
     * <p>实测触发者：Create 的 {@code BeltBlockEntity} 能力提供者 ——
     * {@code registerCapabilities} 里的 lambda 会调 {@code initializeItemHandler()}，
     * 后者 {@code level.getBlockEntity(controller)} 去拿传送带控制器所在区块的 BE。
     * 传送带完全落在一个区块内时 controller 同区块 → 命中 {@code currentlyLoading} 旁路 → 不卡；
     * 跨区块时 controller 在别的区块 → 走同步加载 → 死锁（复现：进世界即服务端线程卡死）。</p>
     *
     * <p>因此这里<b>只写内存集合</b>（唯一允许的调用是 {@code level.dimension()} 这类纯 getter），
     * 真正的扫描交给 {@link #flushPendingRescans} —— 它在 {@code ServerTickEvent.Pre} 里被调用，
     * 主线程不在任何区块任务内部，同步加载才是合法的。
     * 回归守卫见 {@code ContainerChunkCacheChunkLoadTest}。</p>
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            schedulePendingRescan(level, event.getChunk().getPos());
        }
    }

    /**
     * 区块卸载事件 —— 不立即从缓存中移除。
     *
     * 为什么不在卸载时移除：
     *   processLevelContainers 在 ServerTickEvent.Pre 中执行，
     *   而 ChunkEvent.Load 在 ServerLevel.tick() 中触发（晚于 Pre）。
     *   如果卸载时移除，重新加载后 processLevelContainers 在同一个 tick 的 Pre 阶段
     *   找不到该区块，需要等到下一个 tick 才能处理。
     *   保留在缓存中的区块会在 processLevelContainers 中通过 getChunkNow()
     *   自行检测并移除，因此不需要在卸载事件中主动清理。
     *
     * 兜底：cleanupStaleEntries 定期清理真正已卸载的区块。
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        // 不立即移除，由 cleanupStaleEntries 负责清理
    }

    /**
     * 方块放置事件 —— 安排该区块在下一 tick 重扫。
     *
     * <p>不在此处判断是否为容器方块：{@code EntityPlaceEvent} 触发时 BlockEntity
     * 可能还没就绪，capability 查询会返回 null 从而误判为非容器。
     * 是否真的有容器交由 {@link #scanChunkForContainers} 在下一 tick 判定。</p>
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            schedulePendingRescan(level, event.getPos());
        }
    }

    /**
     * 方块破坏事件 —— 清理该位置的容器级数据，并安排区块在下一 tick 重扫。
     *
     * <p>同样不做 capability 前置判断：破坏时 capability 可能已失效，
     * 前置守卫会导致缓存残留失效条目。</p>
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ContainerLivingItemHandler.removeDataByPos(level, event.getPos());
            schedulePendingRescan(level, event.getPos());
        }
    }

    private void schedulePendingRescan(ServerLevel level, BlockPos pos) {
        schedulePendingRescan(level, new ChunkPos(pos));
    }

    /**
     * 登记一个待重扫区块。
     *
     * <p><b>只写内存集合，不碰世界</b> —— 该方法被 {@link #onChunkLoad} 调用，
     * 而那里处在区块加载任务内部（见 {@link #onChunkLoad} 的红线说明）。</p>
     */
    private void schedulePendingRescan(ServerLevel level, ChunkPos chunkPos) {
        pendingRescans.computeIfAbsent(level.dimension(), k -> new ObjectOpenHashSet<>())
                      .add(chunkPos);
    }

    /**
     * 执行本 tick 累积的延后重扫（由 tick 循环在处理容器前调用）。
     *
     * <p>⚠️ <b>这里是唯一允许做能力查询的重扫入口。</b>它在 {@code ServerTickEvent.Pre} 里被调用，
     * 主线程不在任何区块任务内部，因此即使某个模组的能力提供者触发同步区块加载也是合法的。
     * <b>不要把 {@link #scanChunkForContainers} 挪回事件回调里</b> —— 见 {@link #onChunkLoad} 的红线说明。</p>
     *
     * <p>每 tick 限量 {@value #MAX_RESCANS_PER_TICK} 个：区块加载事件会成批涌入
     * （视距 12 约 600 个区块，远距离传送 / 调大视距后更集中），一次性全扫会把能力查询
     * 堆到同一 tick。剩余的留到下一 tick 继续，不会丢。</p>
     *
     * @param level 服务端世界
     */
    public void flushPendingRescans(ServerLevel level) {
        Set<ChunkPos> pending = pendingRescans.remove(level.dimension());
        if (pending == null || pending.isEmpty()) return;

        Set<ChunkPos> carryOver = null;
        int scanned = 0;

        for (ChunkPos cPos : pending) {
            if (scanned >= MAX_RESCANS_PER_TICK) {
                if (carryOver == null) carryOver = new ObjectOpenHashSet<>();
                carryOver.add(cPos);
                continue;
            }
            scanned++;

            // getChunkNow（非阻塞）：绝不在 tick 里主动加载区块
            LevelChunk chunk = level.getChunkSource().getChunkNow(cPos.x, cPos.z);
            if (chunk != null) {
                scanChunkForContainers(level, chunk);
            }
            // chunk == null：区块还没到 FULL 或被卸载 —— 丢弃即可，不会漏：
            // 它真正加载完成时会再触发一次 ChunkEvent.Load 重新登记。
        }

        if (carryOver != null) {
            pendingRescans.computeIfAbsent(level.dimension(), k -> new ObjectOpenHashSet<>())
                          .addAll(carryOver);
        }
    }

    /**
     * 扫描指定区块，检查是否包含容器方块实体（通过 IItemHandler 能力），更新缓存。
     *
     * <p>⚠️ <b>只能在"不在区块加载任务内部"的上下文里调用</b>（当前唯一调用点：
     * {@link #flushPendingRescans}）。这里的 {@code level.getCapability(...)} 会执行任意模组
     * 注册的能力提供者，其中有些（如 Create 传送带）会去查别的区块的方块实体、
     * 从而触发同步区块加载。在 {@code ChunkEvent.Load} 里这么干会死锁 —— 见 {@link #onChunkLoad}。</p>
     *
     * @param level 服务端世界
     * @param chunk 要扫描的区块
     */
    private void scanChunkForContainers(ServerLevel level, LevelChunk chunk) {
        ResourceKey<Level> dim = level.dimension();
        ChunkPos pos = chunk.getPos();

        boolean hasContainer = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null) != null) {
                hasContainer = true;
                break;
            }
        }

        Set<ChunkPos> chunkSet = chunkCache.computeIfAbsent(dim, k -> new ObjectOpenHashSet<>());
        if (hasContainer) {
            chunkSet.add(pos);
        } else {
            chunkSet.remove(pos);
        }
    }

    /**
     * 获取指定维度中**当前可处理**的容器区块坐标集合的快照副本。
     *
     * <p>「可处理」= 缓存命中 **且** 该区块处于 <b>ticking 区</b>
     * （{@code ServerChunkCache.isPositionTicking} ⇒ {@code FullChunkStatus.BLOCK_TICKING}，
     * ticket level ≤ 32）。</p>
     *
     * <p>⚠️ <b>为什么必须过滤掉"已加载但不 tick"的最外一圈（33 圈）</b>（2026-09-18）：</p>
     * <ul>
     *   <li>各活物品逻辑会读<b>相邻一格</b>的方块（红石 {@code getSignal}、活漏斗邻居容器、
     *       大箱子另一半、活水车下方一格）。33 圈的邻居可能落在 34+ 的<b>生成余量圈（未加载）</b>
     *       ⇒ 读邻居会触发强制加载。</li>
     *   <li>反过来，ticking 区块是**可证明安全**的：{@code ChunkMap.prepareTickingChunk} 用
     *       {@code getChunkRangeFuture(holder, 1, ChunkStatus.FULL)} ⇒ ticking 区块的
     *       <b>3×3 邻域必然已是 FULL</b> ⇒ 一格距离的邻居永远已加载，读它免费。
     *       这正是原版"BE 只在 ≤31 跑 ⇒ 邻居必然已加载"那条不变量的现代形式。</li>
     *   <li>附带效果：被强制加载/钉住的 33 圈区块不再被处理 ⇒ 不再读它的邻居
     *       ⇒ <b>加载区无法逐圈外扩</b>。</li>
     * </ul>
     *
     * <p>注意：<b>扫描（{@link #flushPendingRescans}）不做这个过滤</b> —— 发现索引要覆盖所有
     * 已加载区块，否则"加载后一直没进 ticking 区"的区块永远发现不到（区块提升到 ticking
     * <b>没有</b>对应事件可登记）。只有"处理"这一步需要过滤。</p>
     *
     * <p>返回副本而非原始集合的视图，确保调用方在遍历时不会被事件回调
     * （如区块加载/卸载）对底层集合的修改影响，避免 ConcurrentModification。</p>
     *
     * @param level 服务端世界
     * @return 可处理区块坐标集合的快照副本；没有则返回空集合
     */
    public Set<ChunkPos> getProcessableChunks(ServerLevel level) {
        Set<ChunkPos> raw = chunkCache.get(level.dimension());
        if (raw == null || raw.isEmpty()) return Collections.emptySet();

        var chunkSource = level.getChunkSource();
        Set<ChunkPos> result = new ObjectOpenHashSet<>(raw.size());
        for (ChunkPos pos : raw) {
            if (chunkSource.isPositionTicking(pos.toLong())) {
                result.add(pos);
            }
        }
        return result;
    }

    /** 清空所有缓存（用于服务端关闭或维度卸载等场景） */
    public void clear() {
        chunkCache.clear();
        lastCleanupTick.clear();
        pendingRescans.clear();
    }

    /** 从缓存中移除指定区块（自清洁，由 tick 循环调用） */
    public void removeChunk(ResourceKey<Level> dim, ChunkPos pos) {
        Set<ChunkPos> chunkSet = chunkCache.get(dim);
        if (chunkSet != null && chunkSet.remove(pos)) {
            PerfMetrics.recordCacheSelfClean();
        }
    }

    /**
     * 定期清理缓存中已不再加载的区块。
     *
     * 与 onChunkUnload 不同，此方法作为兜底机制，防止因异步卸载时序问题
     * 导致已卸载区块残留在缓存中。每隔 intervalTicks 执行一次清理。
     *
     * @param level         服务端世界
     * @param intervalTicks 清理间隔（tick）
     */
    public void cleanupStaleEntries(ServerLevel level, int intervalTicks) {
        ResourceKey<Level> dim = level.dimension();
        long gameTime = level.getGameTime();

        Long lastCleanup = lastCleanupTick.get(dim);
        if (lastCleanup == null) {
            lastCleanupTick.put(dim, gameTime);
            return;
        }
        if (gameTime - lastCleanup < intervalTicks) {
            return;
        }
        lastCleanupTick.put(dim, gameTime);

        Set<ChunkPos> chunkSet = chunkCache.get(dim);
        if (chunkSet == null || chunkSet.isEmpty()) return;

        var toRemove = new java.util.ArrayList<ChunkPos>();
        for (ChunkPos pos : chunkSet) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
                toRemove.add(pos);
            }
        }
        for (ChunkPos pos : toRemove) {
            chunkSet.remove(pos);
        }
        if (!toRemove.isEmpty()) {
            ModLog.CONTAINER.debug("cleanupStaleEntries removed {} chunks in {}", toRemove.size(), dim.location());
        }
    }

    public int getCacheSize(ResourceKey<Level> dim) {
        Set<ChunkPos> chunkSet = chunkCache.get(dim);
        return chunkSet == null ? 0 : chunkSet.size();
    }

    /**
     * 生成一行「缓存 / 加载」统计，供日志与调试命令共用（{@code /living_monitor cache}）。
     *
     * <p>为什么要把 <b>loaded 区块数</b> 和缓存规模一起输出：这是判断"强制加载 / 钉住 /
     * 逐圈外扩"是否真的发生的<b>唯一直接观测量</b>（见 {@link #getProcessableChunks} 的说明）。</p>
     *
     * <p>读法：玩家站着不动时反复采样。`loaded区块` 若持续增长、或明显超过括号里的视距基准
     * （多出来的是出生点/强制加载/被钉住的区块），说明存在"视距之外仍被加载"的区块。</p>
     *
     * @param level 服务端世界
     * @return 形如 {@code dim=overworld 缓存区块=12 可处理=10 loaded区块=631（视距12基准625）}
     */
    public String describeCacheStats(ServerLevel level) {
        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        int baseline = (2 * viewDistance + 1) * (2 * viewDistance + 1);
        return String.format("dim=%s 缓存区块=%d 可处理=%d loaded区块=%d（视距%d基准%d）",
            level.dimension().location(),
            getCacheSize(level.dimension()),
            getProcessableChunks(level).size(),
            level.getChunkSource().getLoadedChunksCount(),
            viewDistance,
            baseline);
    }
}