package com.qiqi.li.living.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TlmClientConfigTest {
    @AfterEach void reset() {
        TlmClientConfig.resetForTest();
    }

    @Test void specBuildsAndUnloadedAccessorUsesConfiguredDefault() {
        assertNotNull(TlmClientConfig.SPEC);
        assertEquals(1.25f, TlmClientConfig.previewScaleMultiplier(), 0.0001f);
        assertEquals("living_item-tlm-client.toml", TlmClientConfig.FILE_NAME);
    }

    @Test void sanitizerClampsInclusiveRange() {
        assertEquals(0.25f, TlmClientConfig.sanitize(-100), 0.0001f);
        assertEquals(0.25f, TlmClientConfig.sanitize(0.25), 0.0001f);
        assertEquals(1.0f, TlmClientConfig.sanitize(1.0), 0.0001f);
        assertEquals(4.0f, TlmClientConfig.sanitize(4.0), 0.0001f);
        assertEquals(4.0f, TlmClientConfig.sanitize(100), 0.0001f);
    }

    @Test void nonFiniteValuesFallBackToDefault() {
        assertEquals(1.25f, TlmClientConfig.sanitize(Double.NaN), 0.0001f);
        assertEquals(1.25f, TlmClientConfig.sanitize(Double.POSITIVE_INFINITY), 0.0001f);
        assertEquals(1.25f, TlmClientConfig.sanitize(Double.NEGATIVE_INFINITY), 0.0001f);
    }

    @Test void cachedValueCanRefreshWithoutReconstructingConsumers() {
        TlmClientConfig.applyForTest(2.5);
        assertEquals(2.5f, TlmClientConfig.previewScaleMultiplier(), 0.0001f);
        TlmClientConfig.applyForTest(1.0);
        assertEquals(1.0f, TlmClientConfig.previewScaleMultiplier(), 0.0001f);
    }
}
