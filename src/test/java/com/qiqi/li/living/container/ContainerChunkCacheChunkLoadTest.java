package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 区块加载死锁回归守卫（2026-09-18）。
 *
 * <p>背景：{@code ChunkEvent.Load} 在区块 FULL 任务的主线程回调里触发
 * （{@code ChunkStatusTasks.full()} → {@code EVENT_BUS.post(...)}）。在这个回调里做世界交互
 * （尤其是能力查询）会执行第三方能力提供者，其中有些会去查别的区块的方块实体 →
 * 触发同步区块加载 → {@code managedBlock} + {@code join} 等一个只能由主线程自己推进的
 * chunk future ⇒ 服务端线程自等自冻结。实测触发者：Create 6.0.10 的 {@code BeltBlockEntity}
 * （跨区块传送带必卡，同区块不卡 —— 原版 {@code ChunkHolder.currentlyLoading} 旁路只覆盖
 * "正在加载的那个区块本身"）。</p>
 *
 * <p>所以这里钉住三条不变量：</p>
 * <ol>
 *   <li><b>红线</b>：{@code onChunkLoad} 里除纯 getter（{@code dimension()}）外不得有任何
 *       level 交互 —— 用 {@code verifyNoMoreInteractions} 把整个方法钉死；</li>
 *   <li><b>不丢</b>：登记的区块会在下一 tick 的 {@code flushPendingRescans} 里被真正扫描，
 *       容器照常进缓存（修死锁不能顺手把容器发现弄丢）；</li>
 *   <li><b>不主动加载</b>：延后重扫只用 {@code getChunkNow}，区块没到 FULL 就跳过。</li>
 * </ol>
 */
class ContainerChunkCacheChunkLoadTest {

    private ContainerChunkCache cache;

    @BeforeEach
    void setUp() {
        cache = ContainerChunkCache.getInstance();
        cache.clear(); // 单例 + 静态状态：跨用例必须清干净，否则用例互相污染
    }

    @AfterEach
    void tearDown() {
        cache.clear();
    }

    // ---------- 替身 ----------
    // ⚠️ 每个 helper 都必须作为独立语句调用：把 stubbing helper 塞进另一个
    //    when(...).thenReturn(...) 的参数里会触发 UnfinishedStubbingException。

    private static ServerLevel mockLevel() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    private static LevelChunk mockChunk(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = Mockito.mock(LevelChunk.class);
        Mockito.when(chunk.getLevel()).thenReturn(level); // ChunkEvent 构造会读它
        Mockito.when(chunk.getPos()).thenReturn(pos);
        return chunk;
    }

    /** 一个「含 1 个容器」的区块替身（能力查询返回非 null → 会被判为容器区块） */
    private static LevelChunk containerChunk(ServerLevel level, ChunkPos pos) {
        BlockPos bePos = new BlockPos(pos.getMinBlockX(), 64, pos.getMinBlockZ());
        BlockEntity be = Mockito.mock(BlockEntity.class);
        Mockito.when(be.getBlockPos()).thenReturn(bePos);

        LevelChunk chunk = mockChunk(level, pos);
        Mockito.when(chunk.getBlockEntities()).thenReturn(Map.of(bePos, be));
        Mockito.when(level.getCapability(Capabilities.ItemHandler.BLOCK, bePos, null))
               .thenReturn(Mockito.mock(IItemHandler.class));
        return chunk;
    }

    /**
     * 让 {@code getChunkNow} 按坐标从 {@code loaded} 里取区块（取不到 = null = 还没到 FULL）。
     * <p>同时把这些区块默认标为 <b>ticking</b>（loaded 且 tick，即"可处理"）——
     * 需要模拟"已加载但不 tick"（33 圈危险带）的用例自行覆盖 {@code isPositionTicking}。</p>
     */
    private static ServerChunkCache mockChunkSource(ServerLevel level, Map<Long, LevelChunk> loaded) {
        ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        Mockito.when(source.getChunkNow(Mockito.anyInt(), Mockito.anyInt()))
               .thenAnswer(inv -> loaded.get(ChunkPos.asLong(
                   inv.getArgument(0, Integer.class), inv.getArgument(1, Integer.class))));
        for (long packed : loaded.keySet()) {
            Mockito.when(source.isPositionTicking(packed)).thenReturn(true);
        }
        Mockito.when(level.getChunkSource()).thenReturn(source);
        return source;
    }

    /** 走完整事件入口登记一个区块（被测路径，不直接调私有方法） */
    private void fireChunkLoad(ServerLevel level, ChunkPos pos) {
        cache.onChunkLoad(new ChunkEvent.Load(mockChunk(level, pos), true));
    }

    // ---------- 1. 红线：事件里不碰世界 ----------

    @Test
    @DisplayName("红线：ChunkEvent.Load 里除 dimension() 外不得有任何 level 交互（否则触发同步区块加载 → 死锁）")
    void chunkLoad_neverTouchesLevel() {
        ServerLevel level = mockLevel();

        fireChunkLoad(level, new ChunkPos(3, 7));

        // dimension() 是纯 getter（登记待重扫需要它）；除此之外一次 level 调用都不许有。
        // 刻意不逐条 verify(never()) —— 列不全，将来在事件里加别的世界调用会漏网。
        Mockito.verify(level).dimension();
        Mockito.verifyNoMoreInteractions(level);
    }

    // ---------- 2. 不丢：下一 tick 照常发现容器 ----------

    @Test
    @DisplayName("不丢：事件只登记，下一 tick flush 时容器照常进缓存")
    void deferredRescan_discoversContainer() {
        ServerLevel level = mockLevel();
        ChunkPos pos = new ChunkPos(3, 7);

        fireChunkLoad(level, pos);
        assertFalse(cache.getProcessableChunks(level).contains(pos),
            "事件阶段不该扫描（扫描会死锁），此时缓存里不应有它");

        LevelChunk chunk = containerChunk(level, pos);
        mockChunkSource(level, Map.of(pos.toLong(), chunk));

        cache.flushPendingRescans(level);

        assertTrue(cache.getProcessableChunks(level).contains(pos),
            "延后重扫必须补上容器发现 —— 修死锁不能把功能一起修掉");
        assertEquals(1, cache.getCacheSize(Level.OVERWORLD));
    }

    // ---------- 3. 限量：剩余留到下一 tick，不丢 ----------

    @Test
    @DisplayName("限量：单 tick 最多扫 MAX_RESCANS_PER_TICK 个，剩余留到下一 tick 且不丢")
    void flush_isBudgeted_remainderCarriedOver() {
        int total = ContainerChunkCache.MAX_RESCANS_PER_TICK + 1;

        ServerLevel level = mockLevel();
        Map<Long, LevelChunk> loaded = new HashMap<>();
        for (int i = 0; i < total; i++) {
            ChunkPos pos = new ChunkPos(i, 0);
            fireChunkLoad(level, pos);
            loaded.put(pos.toLong(), containerChunk(level, pos));
        }
        mockChunkSource(level, loaded);

        cache.flushPendingRescans(level);
        assertEquals(ContainerChunkCache.MAX_RESCANS_PER_TICK, cache.getCacheSize(Level.OVERWORLD),
            "单 tick 只扫限量个（区块加载会成批涌入，避免峰值堆到同一 tick）");

        cache.flushPendingRescans(level);
        assertEquals(total, cache.getCacheSize(Level.OVERWORLD),
            "剩余的必须留到下一 tick 继续，不能丢");
    }

    // ---------- 4. 不主动加载区块 ----------

    @Test
    @DisplayName("不主动加载：区块还没到 FULL → 跳过，不调 getChunk 强载")
    void flush_skipsChunkNotLoadedYet() {
        ServerLevel level = mockLevel();
        ChunkPos pos = new ChunkPos(3, 7);

        fireChunkLoad(level, pos);

        ServerChunkCache source = mockChunkSource(level, Map.of()); // getChunkNow 一律 null

        cache.flushPendingRescans(level);

        assertEquals(0, cache.getCacheSize(Level.OVERWORLD));
        Mockito.verify(source, Mockito.never())
               .getChunk(Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
    }

    // ---------- 5. 只处理 ticking 区（防止跨区块强制加载） ----------

    @Test
    @DisplayName("只处理 ticking 区：已加载但不 tick 的最外一圈（33 圈）被排除，但仍留在缓存里不丢发现")
    void processableChunks_excludesNonTickingButKeepsDiscovery() {
        ServerLevel level = mockLevel();
        ChunkPos ticking = new ChunkPos(1, 1);
        ChunkPos dangerRing = new ChunkPos(2, 2); // 已加载但不 tick = 危险带

        fireChunkLoad(level, ticking);
        fireChunkLoad(level, dangerRing);

        LevelChunk tickingChunk = containerChunk(level, ticking);
        LevelChunk ringChunk = containerChunk(level, dangerRing);
        Map<Long, LevelChunk> loaded = new HashMap<>();
        loaded.put(ticking.toLong(), tickingChunk);
        loaded.put(dangerRing.toLong(), ringChunk);
        ServerChunkCache source = mockChunkSource(level, loaded);
        Mockito.when(source.isPositionTicking(dangerRing.toLong())).thenReturn(false);

        cache.flushPendingRescans(level);
        assertEquals(2, cache.getCacheSize(Level.OVERWORLD), "两个区块都该被发现（扫描不过滤 ticking）");

        var processable = cache.getProcessableChunks(level);
        assertTrue(processable.contains(ticking), "ticking 区块必须被处理");
        assertFalse(processable.contains(dangerRing),
            "已加载但不 tick 的区块必须排除 —— 它的邻居可能落在未加载的生成余量圈，读邻居会强制加载");

        assertEquals(2, cache.getCacheSize(Level.OVERWORLD),
            "排除只影响「处理」不影响「发现」—— 区块提升到 ticking 没有对应事件，剔掉就会永久漏掉");
    }

    // ---------- 6. 可观测性：验证钉住/外扩的唯一直接观测量 ----------

    @Test
    @DisplayName("可观测性：describeCacheStats 同时输出缓存规模与 loaded 区块数（含视距基准）")
    void describeCacheStats_reportsLoadedChunkCount() {
        ServerLevel level = mockLevel();
        MinecraftServer server = Mockito.mock(MinecraftServer.class);
        PlayerList playerList = Mockito.mock(PlayerList.class);
        Mockito.when(level.getServer()).thenReturn(server);
        Mockito.when(server.getPlayerList()).thenReturn(playerList);
        Mockito.when(playerList.getViewDistance()).thenReturn(12);

        ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
        Mockito.when(source.getLoadedChunksCount()).thenReturn(631);
        Mockito.when(level.getChunkSource()).thenReturn(source);

        String line = cache.describeCacheStats(level);

        assertTrue(line.contains("loaded区块=631"), "必须输出 loaded 区块数，否则无法观测钉住/外扩：" + line);
        assertTrue(line.contains("基准625"), "视距 12 的基准 (2×12+1)² = 625，用于判断是否超出：" + line);
    }
}
