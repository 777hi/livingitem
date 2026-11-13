package com.qiqi.li.client.render.tlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabVoiceArbiterTest {
    @Test
    void highestPriorityRequestWinsCurrentTick() {
        var arbiter = new SoulSlabVoiceArbiter();
        arbiter.request(SoulSlabVoiceArbiter.Request.AMBIENT);
        arbiter.request(SoulSlabVoiceArbiter.Request.RESPONSE);
        arbiter.request(SoulSlabVoiceArbiter.Request.FEED);
        assertEquals(SoulSlabVoiceArbiter.Request.FEED, arbiter.take());
        assertNull(arbiter.take());
    }

    @Test
    void lowerPriorityCannotReplaceInteraction() {
        var arbiter = new SoulSlabVoiceArbiter();
        arbiter.request(SoulSlabVoiceArbiter.Request.RESPONSE);
        arbiter.request(SoulSlabVoiceArbiter.Request.AMBIENT);
        assertEquals(SoulSlabVoiceArbiter.Request.RESPONSE, arbiter.take());
    }

    @Test
    void instancesDoNotShareGlobalState() {
        var first = new SoulSlabVoiceArbiter();
        var second = new SoulSlabVoiceArbiter();
        first.request(SoulSlabVoiceArbiter.Request.RESPONSE);
        second.request(SoulSlabVoiceArbiter.Request.FEED);
        assertEquals(SoulSlabVoiceArbiter.Request.RESPONSE, first.take());
        assertEquals(SoulSlabVoiceArbiter.Request.FEED, second.take());
    }

    @Test
    void clearDropsPendingVoice() {
        var arbiter = new SoulSlabVoiceArbiter();
        arbiter.request(SoulSlabVoiceArbiter.Request.FEED);
        arbiter.clear();
        assertNull(arbiter.take());
    }
}
