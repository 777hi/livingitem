package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流体快照同步的边沿状态机守卫（2026-10-04）——
 * 「汲走最后一个源 ⇒ 数据清空 ⇒ 必须下发一次空快照清掉客户端残留渲染」。
 *
 * <p>bug 现场：驱动的 {@code !isEmpty()} 门与 flush 的同名校验把「刚清空」一起挡掉，
 * 服务器不再发包，客户端旧水渲染永不消失。</p>
 */
class FluidFlowServerSyncTest {

    @AfterEach
    void tearDown() {
        FluidFlowServerSync.clearForTest();
    }

    @Test
    @DisplayName("边沿：有数据期间不下发清空；数据清空后恰好下发一次")
    void edge_fireOnceOnDataClear() {
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("k", true), "首次有数据：不需要清空");
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("k", true), "持续有数据：不需要清空");

        assertTrue(FluidFlowServerSync.markActiveAndCheckClear("k", false),
            "数据清空且曾下发过 ⇒ 必须下发一次清空包");

        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("k", false),
            "清空包只发一次（边沿，非电平）");
    }

    @Test
    @DisplayName("从未有数据的容器：任何时刻都不下发清空（无流体容器零开销）")
    void neverActive_neverClears() {
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("cold", false));
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("cold", false));
    }

    @Test
    @DisplayName("重新有数据后再次清空：边沿重新触发（反复汲/倒场景）")
    void edge_rearmsAfterDataReturns() {
        FluidFlowServerSync.markActiveAndCheckClear("k", true);
        assertTrue(FluidFlowServerSync.markActiveAndCheckClear("k", false), "第一次清空触发");

        FluidFlowServerSync.markActiveAndCheckClear("k", true);   // 又倒了一个源
        assertTrue(FluidFlowServerSync.markActiveAndCheckClear("k", false), "再次清空再次触发");
    }
}
