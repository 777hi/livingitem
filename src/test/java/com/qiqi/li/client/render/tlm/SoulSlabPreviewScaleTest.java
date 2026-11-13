package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabPreviewScaleTest {
    @Test void legacyMultiplierReproducesEight() {
        assertEquals(8.0f, SoulSlabPreviewScale.compose(1, 1, 16, 1), 0.0001f);
    }

    @Test void defaultMultiplierProducesTen() {
        assertEquals(10.0f, SoulSlabPreviewScale.compose(1, 1.25f, 16, 1), 0.0001f);
    }

    @Test void everyFactorMultipliesExactlyOnce() {
        float actual = SoulSlabPreviewScale.compose(1.2f, 1.25f, 13, 1.12f);
        float expected = 8.0f * 1.2f * 1.25f * 13.0f / 16.0f * 1.12f;
        assertEquals(expected, actual, 0.0001f);
    }

    @Test void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SoulSlabPreviewScale.compose(Float.NaN, 1, 16, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SoulSlabPreviewScale.compose(1, 0, 16, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SoulSlabPreviewScale.compose(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SoulSlabPreviewScale.compose(1, 1, 16, Float.POSITIVE_INFINITY));
    }
}
