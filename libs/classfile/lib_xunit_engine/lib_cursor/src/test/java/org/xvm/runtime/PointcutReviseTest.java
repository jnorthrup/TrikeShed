package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * revise() — U (Update) path with old nano journaling.
 * Tests that revise replaces an event and bumps the version stamp.
 */
public class PointcutReviseTest {

    @org.junit.jupiter.api.Test
    public void revise_replacesEventAtIndex() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x34, "Original.methodName()", 100);
            assertEquals(1, VmPointcutPublisher.size());

            VmPointcutPublisher.PointcutEvent original = VmPointcutPublisher.peek(0);
            long originalNano = original.nano;

            long t_rev0 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent revised = new VmPointcutPublisher.PointcutEvent(
                    original.seq,
                    System.nanoTime(),
                    0x35,  // changed opcode
                    200,   // changed addr
                    "Revised.methodName()"
            );

            VmPointcutPublisher.revise(0, revised);
            long t_rev1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent after = VmPointcutPublisher.peek(0);
            assertEquals(0x35, after.opcode);
            assertEquals(200, after.addr);
            assertEquals("Revised.methodName()", after.methodName());
            assertTrue(after.nano >= t_rev0 && after.nano <= t_rev1, "revised event nano must be in bounds");
            assertTrue(originalNano >= t0 && originalNano <= t_rev0, "original nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void revise_bumpsVersionStamp() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x10, "ReviseVer.test", 1);
            long v1 = VmPointcutPublisher.versionStamp();

            long t0 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(99, System.nanoTime(), 0x11, 2, "ReviseVer.test2");
            VmPointcutPublisher.revise(0, evt);
            long v2 = VmPointcutPublisher.versionStamp();
            long t1 = System.nanoTime();

            assertTrue(v2 >= v1, "version should increase after revise");
            assertTrue(v2 >= t0 && v2 <= t1, "revised version stamp must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void revise_triggersSubscriber() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x38, "SubRevise.test", 1);

            final int[] count = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> count[0]++);

            long t0 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(1, System.nanoTime(), 0x39, 2, "SubRevise.test2");
            VmPointcutPublisher.revise(0, evt);
            long t1 = System.nanoTime();

            assertEquals(1, count[0], "subscriber should be notified on revise");
            assertTrue(evt.nano >= t0 && evt.nano <= t1, "event nano must be within bounds");

            VmPointcutPublisher.unsubscribe(subId);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void revise_seq_preservedAcrossRevision() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x40, "SeqPreserve.test", 1);

            VmPointcutPublisher.PointcutEvent original = VmPointcutPublisher.peek(0);
            int originalSeq = original.seq;

            long t0 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(
                    originalSeq, System.nanoTime(), 0x41, 2, "SeqPreserve.test2");
            VmPointcutPublisher.revise(0, evt);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent after = VmPointcutPublisher.peek(0);
            assertEquals(originalSeq, after.seq, "seq should be preserved across revise");
            assertTrue(after.nano >= t0 && after.nano <= t1, "nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void journalView_oldNanoAtIndex() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x48, "Journal.test", 1);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent original = VmPointcutPublisher.peek(0);
            long originalNano = original.nano;
            assertTrue(originalNano >= t0 && originalNano <= t1);

            // Revise to trigger journal write
            long t2 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent evt = new VmPointcutPublisher.PointcutEvent(
                    original.seq, System.nanoTime(), 0x49, 2, "Journal.test2");
            VmPointcutPublisher.revise(0, evt);
            long t3 = System.nanoTime();

            long journaled = VmPointcutPublisher.journal().oldNanoAt(0);
            assertEquals(originalNano, journaled, "journal should store old nano before revise");
            assertTrue(evt.nano >= t2 && evt.nano <= t3, "revised event nano must be in bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    private VmPointcutPublisher.PointcutEvent[] drainAll() {
        java.util.ArrayList<VmPointcutPublisher.PointcutEvent> list = new java.util.ArrayList<>();
        VmPointcutPublisher.drain(list::add);
        return list.toArray(new VmPointcutPublisher.PointcutEvent[0]);
    }
}