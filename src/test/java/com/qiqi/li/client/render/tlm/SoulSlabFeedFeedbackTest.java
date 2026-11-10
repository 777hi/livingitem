package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabFeedFeedbackTest {
    @Test void initialValueOnlyBuildsBaseline() {
        var feedback = new SoulSlabFeedFeedback();
        assertFalse(feedback.observe(5));
    }

    @Test void increaseTriggersOnce() {
        var feedback = new SoulSlabFeedFeedback();
        feedback.observe(5);
        assertTrue(feedback.observe(6));
        assertFalse(feedback.observe(6));
    }

    @Test void decreaseResetsBaselineWithoutFeedback() {
        var feedback = new SoulSlabFeedFeedback();
        feedback.observe(5);
        assertFalse(feedback.observe(2));
        assertTrue(feedback.observe(3));
    }

    @Test void corruptNegativeValueActsAsZero() {
        var feedback = new SoulSlabFeedFeedback();
        assertFalse(feedback.observe(-8));
        assertTrue(feedback.observe(1));
    }
}
