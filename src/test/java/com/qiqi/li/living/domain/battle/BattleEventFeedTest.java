package com.qiqi.li.living.domain.battle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BattleEventFeedTest {

    @AfterEach
    void cleanUp() {
        BattleEventFeed.clear("k");
    }

    @Test
    void offerThenDrainIsFifoAndClears() {
        BattleEventFeed.offer("k", new BattleEvent(BattleEvent.Type.SWING, 3));
        BattleEventFeed.offer("k", new BattleEvent(BattleEvent.Type.SWING, 7));
        List<BattleEvent> drained = BattleEventFeed.drain("k");
        assertEquals(2, drained.size());
        assertEquals(3, drained.get(0).slot());
        assertEquals(7, drained.get(1).slot());
        assertTrue(BattleEventFeed.drain("k").isEmpty(), "drain 后必须清空（fire-and-forget）");
    }

    @Test
    void overflowDropsOldestKeepsNewest() {
        for (int i = 0; i < 40; i++) {
            BattleEventFeed.offer("k", new BattleEvent(BattleEvent.Type.SWING, i));
        }
        List<BattleEvent> drained = BattleEventFeed.drain("k");
        assertEquals(32, drained.size(), "队列上限 = 32（演出预算）");
        assertEquals(8, drained.get(0).slot(), "超限丢旧留新：最旧 8 条已丢弃");
        assertEquals(39, drained.get(drained.size() - 1).slot(), "最新一条必须保留");
    }

    @Test
    void nullKeyAndNullEventAreIgnored() {
        BattleEventFeed.offer(null, new BattleEvent(BattleEvent.Type.SWING, 1));
        BattleEventFeed.offer("k", null);
        assertTrue(BattleEventFeed.drain(null).isEmpty());
        assertTrue(BattleEventFeed.drain("k").isEmpty());
        assertTrue(BattleEventFeed.drain("missing-key").isEmpty());
    }

    @Test
    void keysAreIsolated() {
        BattleEventFeed.offer("a", new BattleEvent(BattleEvent.Type.SWING, 1));
        BattleEventFeed.offer("b", new BattleEvent(BattleEvent.Type.SWING, 2));
        assertEquals(1, BattleEventFeed.drain("a").size());
        assertEquals(1, BattleEventFeed.drain("b").size());
        BattleEventFeed.clear("a");
        BattleEventFeed.clear("b");
    }
}
