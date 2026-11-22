package com.qiqi.li.living.domain.battle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 战场事件队列（按 battleKey）—— 事件源（如伤害钩子）在战局 tick 之外喊话的边车。
 *
 * <p>fire-and-forget：无存储、无回放，客户端重演完即弃。队列上限 = 演出预算
 * （五重圈定之四），超限丢旧留新；无观众时 flush 侧照样 drain（丢弃）⇒ 队列不积压。</p>
 */
public final class BattleEventFeed {

    /** 每 battleKey 队列上限；超限丢旧留新。 */
    private static final int CAP = 32;

    private static final Map<String, Deque<BattleEvent>> FEED = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(server -> FEED.clear());
    }

    private BattleEventFeed() {}

    public static void offer(String battleKey, BattleEvent event) {
        if (battleKey == null || event == null) return;
        Deque<BattleEvent> deque = FEED.computeIfAbsent(battleKey, k -> new ArrayDeque<>());
        while (deque.size() >= CAP) {
            deque.pollFirst();
        }
        deque.addLast(event);
    }

    /** 取走该战局全部待发事件；无战局键返回空表。 */
    public static List<BattleEvent> drain(String battleKey) {
        if (battleKey == null) return List.of();
        Deque<BattleEvent> deque = FEED.get(battleKey);
        if (deque == null || deque.isEmpty()) return List.of();
        List<BattleEvent> out = new ArrayList<>(deque);
        deque.clear();
        return out;
    }

    public static void clear(String battleKey) {
        if (battleKey != null) FEED.remove(battleKey);
    }
}
