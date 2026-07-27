package com.qiqi.li.living.perf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 性能监控指标 —— 收集、记录、分析模组运行时的性能数据。
 *
 * <h3>监控指标</h3>
 * <ul>
 *   <li><b>Tick 耗时</b>：处理容器的平均/P99/最大耗时</li>
 *   <li><b>活物品数量</b>：每种活物品的总数</li>
 *   <li><b>功能调用</b>：每种功能被调用的次数</li>
 *   <li><b>对象池</b>：TickContext 对象池命中率</li>
 *   <li><b>传输</b>：活漏斗传输成功/失败次数</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 记录 tick 耗时
 * PerfMetrics.recordTick(elapsedMs);
 *
 * // 记录活物品数量
 * PerfMetrics.addLivingItem("living_chest", 5);
 *
 * // 记录功能调用
 * PerfMetrics.recordFunctionCall("living_furnace");
 *
 * // 记录对象池命中
 * PerfMetrics.recordPoolHit(true);
 *
 * // 打印报告（每 60 秒）
 * PerfMetrics.printReport();
 * }</pre>
 */
public class PerfMetrics {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ===== Tick 耗时统计 =====
    private static final AtomicLong tickTotalMs = new AtomicLong(0);
    private static final AtomicInteger tickCount = new AtomicInteger(0);
    private static final AtomicLong tickMaxMs = new AtomicLong(0);
    private static final AtomicInteger tickOverThreshold = new AtomicInteger(0);
    private static final int TICK_THRESHOLD_MS = 5;

    // ===== 活物品数量统计 =====
    private static final Map<String, AtomicInteger> livingItemCounts = new ConcurrentHashMap<>();

    // ===== 功能调用统计 =====
    private static final Map<String, AtomicInteger> functionCalls = new ConcurrentHashMap<>();

    // ===== 对象池统计 =====
    private static final AtomicLong poolHits = new AtomicLong(0);
    private static final AtomicLong poolMisses = new AtomicLong(0);

    // ===== 传输统计 =====
    private static final AtomicLong transferSuccess = new AtomicLong(0);
    private static final AtomicLong transferFail = new AtomicLong(0);

    // ===== 报告控制 =====
    private static final long REPORT_INTERVAL_MS = 60_000; // 60 秒
    private static volatile long lastReportTime = System.currentTimeMillis();

    /**
     * 记录 tick 耗时。
     *
     * @param elapsedMs 耗时（毫秒）
     */
    public static void recordTick(long elapsedMs) {
        tickTotalMs.addAndGet(elapsedMs);
        tickCount.incrementAndGet();
        tickMaxMs.accumulateAndGet(elapsedMs, Math::max);
        if (elapsedMs > TICK_THRESHOLD_MS) {
            tickOverThreshold.incrementAndGet();
        }
    }

    /**
     * 增加活物品计数。
     *
     * @functionId 功能 ID（如 "living_chest"）
     * @param count 数量
     */
    public static void addLivingItem(String functionId, int count) {
        livingItemCounts.computeIfAbsent(functionId, k -> new AtomicInteger(0))
                        .addAndGet(count);
    }

    /**
     * 记录功能调用。
     *
     * @param functionId 功能 ID
     */
    public static void recordFunctionCall(String functionId) {
        functionCalls.computeIfAbsent(functionId, k -> new AtomicInteger(0))
                     .incrementAndGet();
    }

    /**
     * 记录对象池命中/未命中。
     *
     * @param hit true 表示命中，false 表示未命中
     */
    public static void recordPoolHit(boolean hit) {
        if (hit) {
            poolHits.incrementAndGet();
        } else {
            poolMisses.incrementAndGet();
        }
    }

    /**
     * 记录传输成功/失败。
     *
     * @param success true 表示成功，false 表示失败
     */
    public static void recordTransfer(boolean success) {
        if (success) {
            transferSuccess.incrementAndGet();
        } else {
            transferFail.incrementAndGet();
        }
    }

    /**
     * 检查是否需要打印报告（每 60 秒一次）。
     *
     * @return true 表示应该打印报告
     */
    public static boolean shouldReport() {
        long now = System.currentTimeMillis();
        if (now - lastReportTime >= REPORT_INTERVAL_MS) {
            lastReportTime = now;
            return true;
        }
        return false;
    }

    /**
     * 打印性能报告并重置计数器。
     */
    public static synchronized void printReport() {
        int count = tickCount.get();
        if (count == 0) {
            return; // 没有数据，跳过
        }

        long totalMs = tickTotalMs.get();
        long maxMs = tickMaxMs.get();
        long avgMs = count > 0 ? totalMs / count : 0;
        int overThreshold = tickOverThreshold.get();

        long totalPool = poolHits.get() + poolMisses.get();
        double poolHitRate = totalPool > 0 ? (poolHits.get() * 100.0 / totalPool) : 0;

        long totalTransfer = transferSuccess.get() + transferFail.get();
        double transferSuccessRate = totalTransfer > 0 ? (transferSuccess.get() * 100.0 / totalTransfer) : 0;

        LOGGER.info("=== 性能报告 (过去 60 秒) ===");
        LOGGER.info("[Tick] 调用次数: {}, 平均耗时: {}ms, P99: {}ms, 最大: {}ms, 超阈值: {}次",
            count, avgMs, estimateP99(), maxMs, overThreshold);

        LOGGER.info("[活物品] {}", formatLivingItems());

        LOGGER.info("[功能调用] {}", formatFunctionCalls());

        LOGGER.info("[对象池] 命中率: {}% (命中: {}, 未命中: {})",
            String.format("%.1f", poolHitRate), poolHits.get(), poolMisses.get());

        LOGGER.info("[传输] 成功率: {}% (成功: {}, 失败: {})",
            String.format("%.1f", transferSuccessRate), transferSuccess.get(), transferFail.get());

        LOGGER.info("=== 报告结束 ===");

        // 重置计数器
        reset();
    }

    /**
     * 估算 P99 耗时（简化版：取 max 的 80% 作为近似值）。
     */
    private static long estimateP99() {
        long max = tickMaxMs.get();
        return max > 0 ? (long) (max * 0.8) : 0;
    }

    /**
     * 格式化活物品数量。
     */
    private static String formatLivingItems() {
        if (livingItemCounts.isEmpty()) return "无数据";
        StringBuilder sb = new StringBuilder();
        livingItemCounts.forEach((id, count) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(id).append(": ").append(count.get());
        });
        return sb.toString();
    }

    /**
     * 格式化功能调用。
     */
    private static String formatFunctionCalls() {
        if (functionCalls.isEmpty()) return "无数据";
        StringBuilder sb = new StringBuilder();
        functionCalls.forEach((id, count) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(id).append(": ").append(count.get());
        });
        return sb.toString();
    }

    /**
     * 重置所有计数器。
     */
    private static void reset() {
        tickTotalMs.set(0);
        tickCount.set(0);
        tickMaxMs.set(0);
        tickOverThreshold.set(0);
        livingItemCounts.clear();
        functionCalls.clear();
        poolHits.set(0);
        poolMisses.set(0);
        transferSuccess.set(0);
        transferFail.set(0);
    }

    /**
     * 重置报告时间（用于测试）。
     */
    public static void resetReportTime() {
        lastReportTime = System.currentTimeMillis();
    }
}