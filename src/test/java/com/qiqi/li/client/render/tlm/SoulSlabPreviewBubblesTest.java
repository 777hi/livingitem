package com.qiqi.li.client.render.tlm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/** 我方图片分支判定，不执行第三方生成/绘制。 */
class SoulSlabPreviewBubblesTest {
    private boolean generate(boolean visible, boolean enabled, boolean empty, long tick,
                             long offset, int rate, int image, int text, RandomSource random) {
        return SoulSlabPreviewBubbles.shouldGenerate(visible, enabled, empty, tick, offset, rate, image, text, random);
    }

    @Test void visibleEnabledEmptyAtCheckGeneratesImage() {
        assertTrue(generate(true, true, true, 1200, 0, 1200, 10, 0, RandomSource.create(1)));
    }

    @Test void disabledOrInvisibleDoesNotConsumeRandomness() {
        RandomSource random = mock(RandomSource.class);
        assertFalse(generate(false, true, true, 1200, 0, 1200, 10, 10, random));
        assertFalse(generate(true, false, true, 1200, 0, 1200, 10, 10, random));
        verifyNoInteractions(random);
    }

    @Test void existingBubbleDoesNotAddAnother() {
        RandomSource random = mock(RandomSource.class);
        assertFalse(generate(true, true, false, 1200, 0, 1200, 10, 10, random));
        verifyNoInteractions(random);
    }

    @Test void onlyPeriodicCheckCanGenerate() {
        RandomSource random = mock(RandomSource.class);
        assertFalse(generate(true, true, true, 1199, 0, 1200, 10, 10, random));
        verifyNoInteractions(random);
    }

    @Test void identityOffsetStaggersPreviewChecks() {
        assertTrue(generate(true, true, true, 1190, 10, 1200, 10, 0, RandomSource.create(1)));
        assertTrue(generate(true, true, true, 10, -10, 1200, 10, 0, RandomSource.create(1)));
    }

    @Test void zeroImageAndZeroTotalSafelyDisableGeneration() {
        RandomSource random = mock(RandomSource.class);
        assertFalse(generate(true, true, true, 1200, 0, 1200, 0, 10, random));
        assertFalse(generate(true, true, true, 1200, 0, 1200, 0, 0, random));
        verifyNoInteractions(random);
    }

    @Test void invalidCheckRateOrWeightsDoNotCallRandom() {
        RandomSource random = mock(RandomSource.class);
        assertFalse(generate(true, true, true, 0, 0, 0, 10, 10, random));
        assertFalse(generate(true, true, true, 0, 0, -1, 10, 10, random));
        assertFalse(generate(true, true, true, 0, 0, 1200, 10, -1, random));
        assertFalse(generate(true, true, true, 0, 0, 1200, Integer.MAX_VALUE, 1, random));
        verifyNoInteractions(random);
    }

    @Test void originalWeightedTextBranchIsSkippedRatherThanGenerated() {
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(20)).thenReturn(9, 10);
        assertTrue(generate(true, true, true, 1200, 0, 1200, 10, 10, random));
        assertFalse(generate(true, true, true, 1200, 0, 1200, 10, 10, random));
        verify(random, times(2)).nextInt(20);
    }

    @Test void largeClockAndOffsetsDoNotOverflowModulo() {
        assertTrue(generate(true, true, true, Long.MAX_VALUE, -Long.MAX_VALUE, 1200, 10, 0, RandomSource.create(1)));
    }
}
