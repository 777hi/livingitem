package com.qiqi.li.living.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定向广播去重表的跨维度合并不变量（2026-10-23 定，2026-10-26 抽成共享工具）。
 *
 * <h3>守什么</h3>
 * <p>四个同步器（{@code LivingToolHostSync} / {@code LivingToolPlayerSync} /
 * {@code BeaconChestHost} / {@code BeaconPlayerHostSync}）都是<b>逐维度</b>调用的
 * （主世界 / 地狱 / 末地），而去重表是<b>全服一张</b>。原来的写法每维度
 * {@code clear()} 再 {@code putAll(next)}，把其它维度玩家的记录一起抹掉 ⇒ 他们下一 tick
 * 查到 {@code null} ⇒ 判「有变化」⇒ <b>每 tick 重发一个包</b>。</p>
 *
 * <p>2026-10-23 在 K2 真机日志实录：单条 INFO 刷了 3803 次。当时只在 K2 修了，
 * 另外三个同步器照抄旧写法又活了三天 —— 所以现在把口径抽到 {@link BroadcastDedup}
 * 并<b>只留这一份</b>测试：正确性由"四个调用点调的是同一个函数"保证，
 * 复制三份测试反而是噪音。</p>
 *
 * <p>用 {@code String} 当值类型是刻意的：本类测的是合并口径，与包内容无关。
 * 旧版用 {@code List.of()} 当值，三个用例的值全等 ⇒ 「覆盖」那条其实是恒真的，
 * 现在换成可区分的字符串，它才真的在测覆盖。</p>
 */
class BroadcastDedupTest {

    private static final UUID IN_OVERWORLD = UUID.randomUUID();
    private static final UUID IN_NETHER = UUID.randomUUID();
    private static final UUID OFFLINE = UUID.randomUUID();

    @Test
    @DisplayName("其它维度玩家的记录必须保留（逐维度 flush 不能互清）")
    void keepsOtherDimensionPlayers() {
        Map<UUID, String> previous = new HashMap<>();
        previous.put(IN_OVERWORLD, "old-overworld");
        previous.put(IN_NETHER, "nether-only");

        // 本维度（主世界）本 tick 只有 IN_OVERWORLD；IN_NETHER 在别的维度
        Map<UUID, String> next = Map.of(IN_OVERWORLD, "new-overworld");
        Set<UUID> online = Set.of(IN_OVERWORLD, IN_NETHER);

        Map<UUID, String> merged = BroadcastDedup.merge(previous, next, online);

        assertTrue(merged.containsKey(IN_NETHER),
            "其它维度的记录被清 ⇒ 该玩家每 tick 重发一个包（2026-10-23 真机实录 3803 次）");
        assertTrue(merged.containsKey(IN_OVERWORLD));
    }

    @Test
    @DisplayName("离线玩家的记录必须淘汰（否则跨存档残留）")
    void dropsOfflinePlayers() {
        Map<UUID, String> previous = new HashMap<>();
        previous.put(IN_OVERWORLD, "a");
        previous.put(OFFLINE, "b");

        Map<UUID, String> next = Map.of(IN_OVERWORLD, "a");
        Set<UUID> online = Set.of(IN_OVERWORLD);

        Map<UUID, String> merged = BroadcastDedup.merge(previous, next, online);

        assertFalse(merged.containsKey(OFFLINE), "离服玩家的记录要淘汰，否则无限累积");
    }

    @Test
    @DisplayName("本维度的新记录覆盖旧值")
    void overwritesThisDimension() {
        Map<UUID, String> previous = new HashMap<>();
        previous.put(IN_OVERWORLD, "old");

        Map<UUID, String> next = new HashMap<>();
        next.put(IN_OVERWORLD, "new");

        Map<UUID, String> merged =
            BroadcastDedup.merge(previous, next, Set.of(IN_OVERWORLD));

        assertEquals(1, merged.size());
        assertEquals("new", merged.get(IN_OVERWORLD), "本 tick 的结果必须覆盖旧记录");
    }
}
