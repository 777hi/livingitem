package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabBooleanFeedbackTest {
    @Test
    void firstObservationOnlyBuildsBaseline() {
        var feedback = new SoulSlabBooleanFeedback();
        assertFalse(feedback.observe(true));
        assertFalse(feedback.observe(true));
    }

    @Test
    void bothEdgesTriggerExactlyOnce() {
        var feedback = new SoulSlabBooleanFeedback();
        feedback.observe(false);
        assertTrue(feedback.observe(true));
        assertFalse(feedback.observe(true));
        assertTrue(feedback.observe(false));
        assertFalse(feedback.observe(false));
    }
}
