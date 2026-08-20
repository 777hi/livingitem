package com.qiqi.li.living.perf;

import com.qiqi.li.logging.ModLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 性能监控指标 —— 收集、记录、分析模组运行时的性能数据。
 *
 * <h3>监控子系统</h3>
 * <ul>
 *   <li><b>Container</b>：容器处理耗时、活物品数量、功能调用次数</li>
 *   <li><b>Teleport</b>：传送次数、区块加载耗时、总传送耗时</li>
 *   <li><b>Cache</b>：容器区块缓存大小、自清理移除数</li>
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

    // ===== 容器区块缓存 =====
    private static final AtomicInteger cacheSelfCleanRemoves = new AtomicInteger(0);
    private static final AtomicInteger cacheCurrentSize = new AtomicInteger(0);

    // ===== 传输 =====
    private static final AtomicLong transferSuccess = new AtomicLong(0);
    private static final AtomicLong transferFail = new AtomicLong(0);

    // ===== processContext 分阶段耗时 =====
    private static final Map<String, PhaseStats> phaseStats = new ConcurrentHashMap<>();

    private static class PhaseStats {
        final AtomicLong totalMs = new AtomicLong(0);
        final AtomicLong maxMs = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);
    }

    // ===== 报告控制 =====
    private static final long REPORT_INTERVAL_MS = 60_000;
    private static volatile long lastReportTime = System.currentTimeMillis();

    // ===== P99 估算（环形缓冲区） =====
    private static final int P99_BUFFER_SIZE = 1024;
    private static final AtomicLongArray p99Buffer = new AtomicLongArray(P99_BUFFER_SIZE);
    private static final AtomicInteger p99Index = new AtomicInteger(0);

    static {
        for (int i = 0; i < P99_BUFFER_SIZE; i++) {
            p99Buffer.set(i, -1);
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
        p99Buffer.set(idx, elapsedMs);
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
    // 容器区块缓存
    // ══════════════════════════════════════════════

    public static void recordCacheSelfClean() {
        cacheSelfCleanRemoves.incrementAndGet();
    }

    public static void updateCacheSize(int size) {
        cacheCurrentSize.set(size);
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
    // processContext 分阶段耗时
    // ══════════════════════════════════════════════

    public static void recordPhase(String phase, long elapsedMs) {
        PhaseStats stats = phaseStats.computeIfAbsent(phase, k -> new PhaseStats());
        stats.totalMs.addAndGet(elapsedMs);
        stats.count.incrementAndGet();
        stats.maxMs.accumulateAndGet(elapsedMs, Math::max);
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
        printCacheSection();
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

        if (!phaseStats.isEmpty()) {
            StringBuilder sb = new StringBuilder("[Phases]");
            phaseStats.forEach((phase, stats) -> {
                int n = stats.count.get();
                if (n == 0) return;
                sb.append(" ").append(phase).append(": avg=").append(stats.totalMs.get() / n)
                  .append("ms max=").append(stats.maxMs.get()).append("ms calls=").append(n);
            });
            ModLog.PERF.info(sb.toString());
        }
    }

    private static void printCacheSection() {
        int size = cacheCurrentSize.get();
        int removes = cacheSelfCleanRemoves.get();
        ModLog.PERF.info("[Cache] currentSize={}, selfCleanRemoves={}", size, removes);
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
            long val = p99Buffer.get(i);
            if (val >= 0 && idx < validCount) {
                sorted[idx++] = val;
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

        cacheSelfCleanRemoves.set(0);
        transferSuccess.set(0);
        transferFail.set(0);
        phaseStats.clear();
        for (int i = 0; i < P99_BUFFER_SIZE; i++) {
            p99Buffer.set(i, -1);
        }
        p99Index.set(0);
    }

    public static void resetReportTime() {
        lastReportTime = System.currentTimeMillis();
    }
}