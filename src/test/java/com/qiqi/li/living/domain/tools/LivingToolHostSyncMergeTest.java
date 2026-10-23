package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.tools.LivingToolHostPacket.Entry;

/**
 * K2 去重记录的合并不变量（2026-10-23）。
 *
 * <h3>守什么</h3>
 * <p>{@code LivingToolHostSync#flush} 是<b>逐维度</b>调用的（主世界/地狱/末地），
 * 而去重表 {@code lastSent} 是<b>全服一张</b>。原来的实现每维度 flush 都
 * {@code lastSent.clear()}，把其它维度玩家的记录也抹掉 ⇒ 那些玩家下一 tick
 * {@code lastSent.get(uuid)} 变 null ⇒ 判「有变化」⇒ <b>每 tick 重发一个空包</b>。
 * 真机日志实录：单条 INFO 刷了 3803 次。</p>
 *
 * <p>{@code mergeRecords} 把这条不变量抽成纯函数 ⇒ 可以脱离 Minecraft 服务端对象单测。
 * 改回 {@code clear()} 时，本测试红。</p>
 */
class LivingToolHostSyncMergeTest {

    private static final UUID IN_OVERWORLD = UUID.randomUUID();
    private static final UUID IN_NETHER = UUID.randomUUID();
    private static final UUID OFFLINE = UUID.randomUUID();

    /** 造一条「某玩家的上次记录」（内容无关紧要，只比键的存在与身份）。 */
    private static List<Entry> sent() {
        return List.of();
    }

    @Test
    @DisplayName("其它维度玩家的记录必须保留（逐维度 flush 不能互清）")
    void keepsOtherDimensionPlayers() {
        Map<UUID, List<Entry>> previous = new HashMap<>();
        previous.put(IN_OVERWORLD, sent());
        previous.put(IN_NETHER, sent());

        // 本维度（主世界）本 tick 只有 IN_OVERWORLD；IN_NETHER 在别的维度
        Map<UUID, List<Entry>> next = Map.of(IN_OVERWORLD, sent());
        Set<UUID> online = Set.of(IN_OVERWORLD, IN_NETHER);

        Map<UUID, List<Entry>> merged =
            LivingToolHostSync.mergeRecords(previous, next, online);

        assertTrue(merged.containsKey(IN_NETHER),
            "其它维度的记录被清 ⇒ 该玩家每 tick 重发空包（2026-10-23 真机实录）");
        assertTrue(merged.containsKey(IN_OVERWORLD));
    }

    @Test
    @DisplayName("离线玩家的记录必须淘汰（否则跨存档残留）")
    void dropsOfflinePlayers() {
        Map<UUID, List<Entry>> previous = new HashMap<>();
        previous.put(IN_OVERWORLD, sent());
        previous.put(OFFLINE, sent());

        Map<UUID, List<Entry>> next = Map.of(IN_OVERWORLD, sent());
        Set<UUID> online = Set.of(IN_OVERWORLD);

        Map<UUID, List<Entry>> merged =
            LivingToolHostSync.mergeRecords(previous, next, online);

        assertFalse(merged.containsKey(OFFLINE), "离服玩家的记录要淘汰，否则无限累积");
    }

    @Test
    @DisplayName("本维度的新记录覆盖旧值")
    void overwritesThisDimension() {
        List<Entry> oldValue = sent();
        List<Entry> newValue = sent();
        Map<UUID, List<Entry>> previous = new HashMap<>();
        previous.put(IN_OVERWORLD, oldValue);

        Map<UUID, List<Entry>> next = new HashMap<>();
        next.put(IN_OVERWORLD, newValue);

        Map<UUID, List<Entry>> merged =
            LivingToolHostSync.mergeRecords(previous, next, Set.of(IN_OVERWORLD));

        assertEquals(1, merged.size());
        assertEquals(newValue, merged.get(IN_OVERWORLD), "本 tick 的结果必须覆盖旧记录");
    }
}
