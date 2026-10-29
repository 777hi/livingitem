package com.qiqi.li.living.util;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import com.qiqi.li.logging.ModLog;

/**
 * 服务端 tick 异常的限频报告器。
 *
 * <p>首次完整栈；同 key 在窗口内只计数；每窗口输出一次汇总；key 数有上限
 * （LRU 淘汰，防止隔离机制自己泄漏）。<b>仅服务端线程调用，不做同步。</b></p>
 *
 * <p>隔离层只接 {@link RuntimeException}；{@link Error}（OOM / 栈溢出）必须照常抛出。</p>
 */
public final class FailureThrottle {

    private static final long DEFAULT_LOG_WINDOW_MS = 60_000;
    private static final long DEFAULT_SUMMARY_WINDOW_MS = 300_000;
    private static final int DEFAULT_MAX_KEYS = 256;

    private static final Map<String, Failure> FAILURES = new LinkedHashMap<>(16, 0.75f, true);
    private static long logWindowMs = DEFAULT_LOG_WINDOW_MS;
    private static long summaryWindowMs = DEFAULT_SUMMARY_WINDOW_MS;
    private static int maxKeys = DEFAULT_MAX_KEYS;
    private static LongSupplier clock = System::currentTimeMillis;
    private static long lastSummaryAt = -1;

    private FailureThrottle() {}

    /** 记录一次被隔离的异常。{@code key} 为 null / 空时归入 {@code unknown}。 */
    public static void report(String key, RuntimeException failure) {
        long now = clock.getAsLong();
        String k = normalize(key);

        Failure f = FAILURES.get(k);
        if (f == null) {
            if (FAILURES.size() >= maxKeys) {
                Iterator<String> it = FAILURES.keySet().iterator();
                it.next();
                it.remove();
            }
            f = new Failure(now);
            FAILURES.put(k, f);
            f.logged++;
            ModLog.CONTAINER.error(
                "LivingItem tick failure isolated at {}（同 key {}ms 内只计数）", k, logWindowMs, failure);
        } else if (now - f.lastLoggedAt >= logWindowMs) {
            int suppressed = f.suppressed;
            f.suppressed = 0;
            f.logged++;
            f.lastLoggedAt = now;
            f.lastSeenAt = now;
            ModLog.CONTAINER.error(
                "LivingItem tick failure isolated at {}（上次报告后抑制 {} 次）", k, suppressed, failure);
        } else {
            f.suppressed++;
            f.lastSeenAt = now;
        }

        if (lastSummaryAt < 0) {
            lastSummaryAt = now;
        }
        if (now - lastSummaryAt >= summaryWindowMs) {
            summarize();
            lastSummaryAt = now;
        }
    }

    private static void summarize() {
        int active = 0;
        int suppressed = 0;
        for (Failure f : FAILURES.values()) {
            if (f.lastSeenAt >= lastSummaryAt) {
                active++;
                suppressed += f.suppressed;
                f.suppressed = 0;
            }
        }
        if (active > 0) {
            ModLog.CONTAINER.warn(
                "LivingItem tick failure summary: {} key(s) still failing, {} suppressed in last {}ms",
                active, suppressed, summaryWindowMs);
        }
    }

    private static String normalize(String key) {
        return key == null || key.isBlank() ? "unknown" : key;
    }

    // ---------------------------------------------------------------- 测试接缝

    /** 仅测试用：缩短窗口 / 注入时钟。 */
    static void configureForTest(long logWindowMs, long summaryWindowMs, int maxKeys, LongSupplier testClock) {
        FailureThrottle.logWindowMs = logWindowMs;
        FailureThrottle.summaryWindowMs = summaryWindowMs;
        FailureThrottle.maxKeys = maxKeys;
        FailureThrottle.clock = testClock;
        FailureThrottle.lastSummaryAt = -1;
        FAILURES.clear();
    }

    /** 仅测试用：恢复默认状态。 */
    static void reset() {
        configureForTest(DEFAULT_LOG_WINDOW_MS, DEFAULT_SUMMARY_WINDOW_MS, DEFAULT_MAX_KEYS,
            System::currentTimeMillis);
    }

    static int trackedKeys() {
        return FAILURES.size();
    }

    /** 不存在返回 -1。 */
    static int loggedCount(String key) {
        Failure f = FAILURES.get(key);
        return f == null ? -1 : f.logged;
    }

    /** 不存在返回 -1。 */
    static long suppressedCount(String key) {
        Failure f = FAILURES.get(key);
        return f == null ? -1 : f.suppressed;
    }

    static long lastSummaryAt() {
        return lastSummaryAt;
    }

    private static final class Failure {
        long lastLoggedAt;
        long lastSeenAt;
        int suppressed;
        int logged;

        Failure(long now) {
            this.lastLoggedAt = now;
            this.lastSeenAt = now;
        }
    }
}
