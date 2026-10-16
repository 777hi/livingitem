package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BeaconTooltipComponent 构建测试（9 柱面板的数据载体）。
 */
class BeaconTooltipComponentTest {

    @Test
    @DisplayName("from(BeaconRuntime) 字段直通 + record equals")
    void fromRuntime() {
        var rt = new BeaconSegment.BeaconRuntime(2,
            new long[]{0, 0, 6, 8, 8, 8, 5, 0, 0},
            new boolean[]{false, false, true, true, true, true, true, true, true},
            BeaconSegment.RegenTier.NONE);

        var c = BeaconTooltipComponent.from(rt);

        assertEquals(2, c.level());
        assertArrayEquals(rt.columnHeights(), c.columnHeights());
        assertArrayEquals(rt.columnExists(), c.columnExists());
        assertEquals(BeaconTooltipComponent.from(rt), c);
    }
}
