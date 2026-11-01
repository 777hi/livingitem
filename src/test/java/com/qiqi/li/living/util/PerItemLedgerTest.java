package com.qiqi.li.living.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PerItemLedger} 均分记账内核 —— 灯泡 / 海绵两域共享的算术契约
 * （设计稿 docs/buffer/活海绵.md §3.1；灯泡行为不变由 RoundTripConservationIT 全量回归守）。
 */
class PerItemLedgerTest {

    /** 测试用大容量（避免绑定具体域的容量常量）。 */
    private static final long BIG_CAP = Long.MAX_VALUE / 2;

    @Test
    @DisplayName("① 均分 floor：6000 存 7 枚 ⇒ 每枚 857、实收 5999（余 1 丢弃）")
    void floorDistribution() {
        var d = PerItemLedger.distribute(0, 7, BIG_CAP, 6_000);
        assertEquals(857, d.newQ());
        assertEquals(857L * 7, d.accepted(), "记账 = 写入 × count（同源，不是 6000）");
    }

    @Test
    @DisplayName("② 剩余不足一整步（per = 0）不写：1 存 7 枚 ⇒ 原样返回")
    void tooSmallIsSkipped() {
        var d = PerItemLedger.distribute(0, 7, BIG_CAP, 1);
        assertEquals(0, d.newQ());
        assertEquals(0, d.accepted());
    }

    @Test
    @DisplayName("③ 容量防御：每枚 990/1000 存 2 枚来 1000 ⇒ room=10，per 截到 10，newQ 顶满")
    void capacityClamp() {
        var d = PerItemLedger.distribute(990, 2, 1000, 1000);
        assertEquals(1000, d.newQ());
        assertEquals(20, d.accepted());
    }

    @Test
    @DisplayName("④ 已满堆不再吃进")
    void fullStackStaysPut() {
        var d = PerItemLedger.distribute(1000, 2, 1000, 5000);
        assertEquals(1000, d.newQ());
        assertEquals(0, d.accepted());
    }

    @Test
    @DisplayName("⑤ 防御边界：count ≤ 0 / delta ≤ 0 原样返回")
    void degenerateInputs() {
        assertEquals(0, PerItemLedger.distribute(5, 0, 100, 100).accepted());
        assertEquals(0, PerItemLedger.distribute(5, 3, 100, 0).accepted());
        assertEquals(0, PerItemLedger.distribute(5, 3, 100, -7).accepted());
        assertEquals(5, PerItemLedger.distribute(5, 0, 100, 100).newQ());
    }

    @Test
    @DisplayName("⑥ 损耗有界：accept − 实收 ≤ count−1（§3 不变量，账本守恒的前提）")
    void lossBound() {
        for (int count = 1; count <= 64; count++) {
            var d = PerItemLedger.distribute(0, count, BIG_CAP, 1_000_000);
            long loss = 1_000_000 - d.accepted();
            assertTrue(loss >= 0 && loss <= count - 1, "count=" + count + " loss=" + loss);
        }
    }
}
