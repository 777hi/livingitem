package com.qiqi.li.living.compat.tlm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdentityRedemptionQueueTest {
    @Test void keysUseObjectIdentityRatherThanEquality() {
        var queue = new IdentityRedemptionQueue<String, Integer>();
        String first = new String("maid");
        String second = new String("maid");
        queue.putIfAbsent(first, 1);
        queue.putIfAbsent(second, 2);
        assertEquals(2, queue.size());
    }

    @Test void duplicateIdentityKeepsFirstRedemption() {
        var queue = new IdentityRedemptionQueue<Object, Integer>();
        Object maid = new Object();
        queue.putIfAbsent(maid, 1);
        queue.putIfAbsent(maid, 2);
        List<IdentityRedemptionQueue.Entry<Object, Integer>> entries = queue.drain();
        assertEquals(1, entries.size());
        assertSame(maid, entries.getFirst().key());
        assertEquals(1, entries.getFirst().value());
    }

    @Test void drainRemovesBeforeExternalProcessing() {
        var queue = new IdentityRedemptionQueue<Object, Integer>();
        queue.putIfAbsent(new Object(), 1);
        List<IdentityRedemptionQueue.Entry<Object, Integer>> entries = queue.drain();
        assertEquals(0, queue.size());
        assertThrows(RuntimeException.class, () -> entries.forEach(entry -> {
            throw new RuntimeException("external listener failed");
        }));
        assertEquals(0, queue.size());
    }

    @Test void clearDropsAllStrongReferences() {
        var queue = new IdentityRedemptionQueue<Object, Integer>();
        queue.putIfAbsent(new Object(), 1);
        queue.clear();
        assertEquals(0, queue.size());
    }
}
