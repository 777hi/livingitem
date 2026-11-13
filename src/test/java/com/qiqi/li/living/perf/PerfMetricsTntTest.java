package com.qiqi.li.living.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerfMetricsTntTest {

    @BeforeEach
    @AfterEach
    void reset() {
        PerfMetrics.resetForTest();
    }

    @Test
    void superApplicationsAreExclusiveAndReportAvoidedWork() {
        PerfMetrics.recordSuperExplosion(true, true, 2);
        PerfMetrics.recordSuperExplosion(false, false, 3);
        PerfMetrics.recordSuperExplosion(false, true, 0);
        PerfMetrics.recordSuperExplosion(false, false, 0);
        PerfMetrics.recordSuperExplosion(false, false, 0);

        assertTrue(PerfMetrics.hasSuperExplosionData());
        assertEquals(
            "[TNT/SUPER] applications=5, contentChanged=1, metadataOnly=1, heightmapOnly=1, noOp=2 (40.0%), pendingBlockEntitiesCleared=5, skippedChunkPackets=2, skippedLightRefreshes=4",
            PerfMetrics.formatTntSummary());
    }

    @Test
    void resetClearsTntCountersAndEmptyReport() {
        assertNull(PerfMetrics.formatTntSummary());
        PerfMetrics.recordSuperExplosion(false, false, 0);
        assertTrue(PerfMetrics.hasSuperExplosionData());

        PerfMetrics.resetForTest();

        assertFalse(PerfMetrics.hasSuperExplosionData());
        assertNull(PerfMetrics.formatTntSummary());
    }
}
