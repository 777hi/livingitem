package com.qiqi.li.living.perf;

import com.qiqi.li.logging.ModLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监控指标 —— 收集、记录、分析模组运行时的性能数据。
 *
 * <h3>监控子系统</h3>
 * <ul>
 *   <li><b>Container</b>：容器处理耗时、活物品数量、功能调用次数</li>
 *   <li><b>Map</b>：地图更新跳过/已加载区块数</li>
 *   <li><b>Teleport</b>：传送次数、区块加载耗时、总传送耗时</li>
 *   <li><b>Cache</b>：容器区块缓存大小、自清理移除数</li>
 *   <li><b>Pool</b>：TickContext 对象池命中率</li>
 *   <li><b>Transfer</b>：活漏斗传输成功/失败次数</li>
 * </ul>
 *
 * <h3>报告格式</h3>
 * 每 60 秒自动打印一次报告，包含各子系统的汇总数据。
 * 报告触发点在 {@link com.qiqi.li.living.container.ContainerLivingItemHandler#processContext} 中。
 */
public class PerfMetrics {

    // ===== Container 处理耗时 =====
    private static final AtomicLong containerTickTotalMs = new AtomicLong(0);
    private static final AtomicInteger containerTickCount = new AtomicInteger(0);
    private static final AtomicLong containerTickMaxMs = new AtomicLong(0);
    private static final AtomicInteger containerOverThreshold = new AtomicInteger(0);
    private static final int CONTAINER_THRESHOLD_MS = 5;

    // ===== 活物品数量 =====
    private static final Map<String, AtomicInteger> livingItemCounts = new ConcurrentHashMap<>();

    // ===== 功能调用 =====
    private static final Map<String, AtomicInteger> functionCalls = new ConcurrentHashMap<>();

    // ===== 地图更新 =====
    private static final AtomicInteger mapChunksSkipped = new AtomicInteger(0);
    private static final AtomicInteger mapChunksLoaded = new AtomicInteger(0);

    // ===== 传送 =====
    private static final AtomicInteger teleportCount = new AtomicInteger(0);
    private static final AtomicLong teleportChunkLoadMs = new AtomicLong(0);
    private static final AtomicLong teleportTotalMs = new AtomicLong(0);
    private static final AtomicLong teleportMaxMs = new AtomicLong(0);
    private static final AtomicInteger teleportSlowChunkLoads = new AtomicInteger(0);

    // ===== 容器区块缓存 =====
    private static final AtomicInteger cacheSelfCleanRemoves = new AtomicInteger(0);
    private static final AtomicInteger cacheCurrentSize = new AtomicInteger(0);

    // ===== 对象池 =====
    private static final AtomicLong poolHits = new AtomicLong(0);
    private static final AtomicLong poolMisses = new AtomicLong(0);

    // ===== 传输 =====
    private static final AtomicLong transferSuccess = new AtomicLong(0);
    private static final AtomicLong transferFail = new AtomicLong(0);

    // ===== 报告控制 =====
    private static final long REPORT_INTERVAL_MS = 60_000;
    private static volatile long lastReportTime = System.currentTimeMillis();

    // ===== P99 估算（环形缓冲区） =====
    private static final int P99_BUFFER_SIZE = 1024;
    private static final long[] p99Buffer = new long[P99_BUFFER_SIZE];
    private static final AtomicInteger p99Index = new AtomicInteger(0);

    static {
        for (int i = 0; i < P99_BUFFER_SIZE; i++) {
            p99Buffer[i] = -1;
        }
    }

    private PerfMetrics() {}

    // ══════════════════════════════════════════════
    // Container 处理
    // ══════════════════════════════════════════════

    public static void recordTick(long elapsedMs) {
        containerTickTotalMs.addAndGet(elapsedMs);
        containerTickCount.incrementAndGet();
        containerTickMaxMs.accumulateAndGet(elapsedMs, Math::max);
        if (elapsedMs > CONTAINER_THRESHOLD_MS) {
            containerOverThreshold.incrementAndGet();
        }
        int idx = p99Index.getAndIncrement() & (P99_BUFFER_SIZE - 1);
        p99Buffer[idx] = elapsedMs;
    }

    public static void addLivingItem(String functionId, int count) {
        livingItemCounts.computeIfAbsent(functionId, k -> new AtomicInteger(0))
                        .addAndGet(count);
    }

    public static void recordFunctionCall(String functionId) {
        functionCalls.computeIfAbsent(functionId, k -> new AtomicInteger(0))
                     .incrementAndGet();
    }

    // ══════════════════════════════════════════════
    // 地图更新
    // ══════════════════════════════════════════════

    public static void recordMapChunkSkipped() {
        mapChunksSkipped.incrementAndGet();
    }

    public static void recordMapChunkLoaded() {
        mapChunksLoaded.incrementAndGet();
    }

    // ══════════════════════════════════════════════
    // 传送
    // ══════════════════════════════════════════════

    public static void recordTeleport(long chunkLoadMs, long totalMs) {
        teleportCount.incrementAndGet();
        teleportChunkLoadMs.addAndGet(chunkLoadMs);
        teleportTotalMs.addAndGet(totalMs);
        teleportMaxMs.accumulateAndGet(totalMs, Math::max);
        if (chunkLoadMs > 50) {
            teleportSlowChunkLoads.incrementAndGet();
        }
    }

    // ══════════════════════════════════════════════
    // 容器区块缓存
    // ══════════════════════════════════════════════

    public static void recordCacheSelfClean() {
        cacheSelfCleanRemoves.incrementAndGet();
    }

    public static void updateCacheSize(int size) {
        cacheCurrentSize.set(size);
    }

    // ══════════════════════════════════════════════
    // 对象池
    // ══════════════════════════════════════════════

    public static void recordPoolHit(boolean hit) {
        if (hit) {
            poolHits.incrementAndGet();
        } else {
            poolMisses.incrementAndGet();
        }
    }

    // ══════════════════════════════════════════════
    // 传输
    // ══════════════════════════════════════════════

    public static void recordTransfer(boolean success) {
        if (success) {
            transferSuccess.incrementAndGet();
        } else {
            transferFail.incrementAndGet();
        }
    }

    // ══════════════════════════════════════════════
    // 报告
    // ══════════════════════════════════════════════

    public static boolean shouldReport() {
        long now = System.currentTimeMillis();
        if (now - lastReportTime >= REPORT_INTERVAL_MS) {
            lastReportTime = now;
            return true;
        }
        return false;
    }

    public static synchronized void printReport() {
        ModLog.PERF.info("=== Performance report (last 60s) ===");

        printContainerSection();
        printMapSection();
        printTeleportSection();
        printCacheSection();
        printPoolSection();
        printTransferSection();

        ModLog.PERF.info("=== End of report ===");

        reset();
    }

    private static void printContainerSection() {
        int count = containerTickCount.get();
        if (count == 0) return;

        long totalMs = containerTickTotalMs.get();
        long maxMs = containerTickMaxMs.get();
        long avgMs = totalMs / count;
        int overThreshold = containerOverThreshold.get();
        long p99 = computeP99();

        ModLog.PERF.info("[Container] calls={}, avg={}ms, P99={}ms, max={}ms, overThreshold={}ms={}",
            count, avgMs, p99, maxMs, CONTAINER_THRESHOLD_MS, overThreshold);
        ModLog.PERF.info("[LivingItems] {}", formatMap(livingItemCounts));
        ModLog.PERF.info("[FunctionCalls] {}", formatMap(functionCalls));
    }

    private static void printMapSection() {
        int skipped = mapChunksSkipped.get();
        int loaded = mapChunksLoaded.get();
        int total = skipped + loaded;
        if (total == 0) return;

        double skipRate = skipped * 100.0 / total;
        ModLog.PERF.info("[Map] totalQueries={}, skipped={} ({}%), loaded={}",
            total, skipped, String.format("%.1f", skipRate), loaded);
    }

    private static void printTeleportSection() {
        int count = teleportCount.get();
        if (count == 0) return;

        long avgChunkMs = teleportChunkLoadMs.get() / count;
        long avgTotalMs = teleportTotalMs.get() / count;
        long maxMs = teleportMaxMs.get();
        int slowLoads = teleportSlowChunkLoads.get();

        ModLog.PERF.info("[Teleport] count={}, avgChunkLoad={}ms, avgTotal={}ms, max={}ms, slowChunkLoads={}",
            count, avgChunkMs, avgTotalMs, maxMs, slowLoads);
    }

    private static void printCacheSection() {
        int size = cacheCurrentSize.get();
        int removes = cacheSelfCleanRemoves.get();
        ModLog.PERF.info("[Cache] currentSize={}, selfCleanRemoves={}", size, removes);
    }

    private static void printPoolSection() {
        long total = poolHits.get() + poolMisses.get();
        if (total == 0) return;

        double hitRate = poolHits.get() * 100.0 / total;
        ModLog.PERF.info("[ObjectPool] hitRate={}%, hits={}, misses={}",
            String.format("%.1f", hitRate), poolHits.get(), poolMisses.get());
    }

    private static void printTransferSection() {
        long total = transferSuccess.get() + transferFail.get();
        if (total == 0) return;

        double successRate = transferSuccess.get() * 100.0 / total;
        ModLog.PERF.info("[Transfer] successRate={}%, success={}, fail={}",
            String.format("%.1f", successRate), transferSuccess.get(), transferFail.get());
    }

    // ══════════════════════════════════════════════
    // P99 计算
    // ══════════════════════════════════════════════

    private static long computeP99() {
        int count = containerTickCount.get();
        if (count == 0) return 0;

        int validCount = Math.min(count, P99_BUFFER_SIZE);
        long[] sorted = new long[validCount];
        int idx = 0;
        for (int i = 0; i < P99_BUFFER_SIZE; i++) {
            if (p99Buffer[i] >= 0 && idx < validCount) {
                sorted[idx++] = p99Buffer[i];
            }
        }
        if (idx == 0) return 0;

        java.util.Arrays.sort(sorted, 0, idx);
        int p99Pos = (int) (idx * 0.99);
        return sorted[Math.min(p99Pos, idx - 1)];
    }

    // ══════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════

    private static String formatMap(Map<String, AtomicInteger> map) {
        if (map.isEmpty()) return "no data";
        StringBuilder sb = new StringBuilder();
        map.forEach((id, count) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(id).append(": ").append(count.get());
        });
        return sb.toString();
    }

    private static void reset() {
        containerTickTotalMs.set(0);
        containerTickCount.set(0);
        containerTickMaxMs.set(0);
        containerOverThreshold.set(0);
        livingItemCounts.clear();
        functionCalls.clear();
        mapChunksSkipped.set(0);
        mapChunksLoaded.set(0);
        teleportCount.set(0);
        teleportChunkLoadMs.set(0);
        teleportTotalMs.set(0);
        teleportMaxMs.set(0);
        teleportSlowChunkLoads.set(0);
        cacheSelfCleanRemoves.set(0);
        poolHits.set(0);
        poolMisses.set(0);
        transferSuccess.set(0);
        transferFail.set(0);
        for (int i = 0; i < P99_BUFFER_SIZE; i++) {
            p99Buffer[i] = -1;
        }
        p99Index.set(0);
    }

    public static void resetReportTime() {
        lastReportTime = System.currentTimeMillis();
    }
}