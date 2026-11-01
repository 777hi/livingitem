package com.qiqi.li.living.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FailureThrottleTest {

    private final AtomicLong now = new AtomicLong();

    @BeforeEach
    void setUp() {
        FailureThrottle.configureForTest(1_000, 10_000, 8, now::get);
    }

    @AfterEach
    void tearDown() {
        FailureThrottle.reset();
    }

    @Test
    @DisplayName("同 key 窗口内只记一次，其余计数抑制")
    void sameKeyWithinWindow_isSuppressed() {
        FailureThrottle.report("a", new RuntimeException("boom"));
        now.set(500);
        FailureThrottle.report("a", new RuntimeException("boom"));

        assertEquals(1, FailureThrottle.loggedCount("a"));
        assertEquals(1, FailureThrottle.suppressedCount("a"));
        assertEquals(1, FailureThrottle.trackedKeys());
    }

    @Test
    @DisplayName("不同 key 各记一次")
    void differentKeys_eachLogged() {
        FailureThrottle.report("a", new RuntimeException());
        FailureThrottle.report("b", new RuntimeException());

        assertEquals(1, FailureThrottle.loggedCount("a"));
        assertEquals(1, FailureThrottle.loggedCount("b"));
        assertEquals(2, FailureThrottle.trackedKeys());
    }

    @Test
    @DisplayName("超过窗口后再次完整报告，并重置抑制计数")
    void afterWindow_logsAgain() {
        FailureThrottle.report("a", new RuntimeException());
        now.set(1_000);
        FailureThrottle.report("a", new RuntimeException());

        assertEquals(2, FailureThrottle.loggedCount("a"));
        assertEquals(0, FailureThrottle.suppressedCount("a"));
    }

    @Test
    @DisplayName("key 数到上限后淘汰最久未使用的")
    void capacity_evictsOldest() {
        FailureThrottle.configureForTest(1_000, 10_000, 2, now::get);
        FailureThrottle.report("a", new RuntimeException());
        FailureThrottle.report("b", new RuntimeException());
        FailureThrottle.report("c", new RuntimeException());

        assertEquals(2, FailureThrottle.trackedKeys());
        assertEquals(-1, FailureThrottle.loggedCount("a"), "最旧的 key 应被淘汰");
        assertEquals(1, FailureThrottle.loggedCount("c"));
    }

    @Test
    @DisplayName("汇总窗口到点后推进时间戳（只汇总仍活跃的 key）")
    void summary_advancesWindow() {
        FailureThrottle.configureForTest(1_000, 5_000, 8, now::get);
        FailureThrottle.report("a", new RuntimeException());
        now.set(4_999);
        FailureThrottle.report("a", new RuntimeException());
        assertEquals(0, FailureThrottle.lastSummaryAt());

        now.set(5_000);
        FailureThrottle.report("a", new RuntimeException());
        assertEquals(5_000, FailureThrottle.lastSummaryAt());
    }

    @Test
    @DisplayName("停服清理已登记（否则跨存档的抑制窗口会吃掉新存档的首条完整栈）")
    void serverStopCleanup_isRegistered() {
        // 2026-11-01 之前这里没有登记：单机换存档时旧 key 仍在「60s 内只计数」窗口里
        // ⇒ 新存档里同一个 BE 再抛，只涨计数不打栈，排查看不到根因。
        String registered = String.join("\n", StaticCacheRegistry.describe());
        assertTrue(registered.contains("FailureThrottle"),
            "FailureThrottle 必须出现在停服 / 客户端清理名单里。当前名单：\n" + registered);
    }
}
