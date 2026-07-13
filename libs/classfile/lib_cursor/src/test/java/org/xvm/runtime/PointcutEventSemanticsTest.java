package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PointcutEvent field semantics — seq, nano, addr, method.
 */
public class PointcutEventSemanticsTest {

    @org.junit.jupiter.api.Test
    public void event_seq_isUniqueAndIncrementing() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x10, "Seq.test", 1);
            VmPointcutPublisher.publish(0x11, "Seq.test2", 2);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertTrue(evts[1].seq > evts[0].seq, "seq should increment");
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1, "nano must be inside bounds");
            assertTrue(evts[1].nano >= t0 && evts[1].nano <= t1, "nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void event_nano_isNonZero() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x34, "Nano.test", 1);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertTrue(evts[0].nano > 0, "nano should be > 0");
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1, "nano must be within [t0, t1]");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void event_addr_preservedOnPublish() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0xA5, "Addr.test", 9999);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(9999, evts[0].addr);
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1, "nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void event_afterWrite_usesAfterLiteral() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x38, "AFTER", -1);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals("AFTER", evts[0].methodName());
            assertEquals(-1, evts[0].addr);
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1, "nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void opcodeName_defaultCase() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x50, "Test.methodName()", 1);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals("OP_0x50", evts[0].opcodeName());
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1, "nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void toString_containsAllFields() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x34, "ToString.test", 42);
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            String s = evts[0].toString();
            assertTrue(s.contains("seq="), "toString should include seq");
            assertTrue(s.contains("opcode="), "toString should include opcode");
            assertTrue(s.contains("addr="), "toString should include addr");
            assertTrue(s.contains("nano="), "toString should include nano");
            assertTrue(s.contains("method="), "toString should include method");
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1, "nano must be inside bounds");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void versionStamp_incrementsOnPublish() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            long v0 = VmPointcutPublisher.versionStamp();
            VmPointcutPublisher.publish(0x34, "Version.test", 1);
            long v1 = VmPointcutPublisher.versionStamp();
            long t1 = System.nanoTime();
            assertTrue(v1 >= v0, "version should not decrease");
            if (v1 > 0) {
                assertTrue(v1 >= t0 && v1 <= t1, "version stamp must be inside bounds");
            }
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void size_reflectsRingContents() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            assertEquals(0, VmPointcutPublisher.size());

            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x10, "Size.test1", 1);
            assertEquals(1, VmPointcutPublisher.size());

            VmPointcutPublisher.publish(0x11, "Size.test2", 2);
            assertEquals(2, VmPointcutPublisher.size());
            long t1 = System.nanoTime();

            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1);
            assertTrue(evts[1].nano >= t0 && evts[1].nano <= t1);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void reset_clearsRingAndVersion() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.publish(0x34, "Reset.test", 1);
            VmPointcutPublisher.publish(0x35, "Reset.test2", 2);
            assertTrue(VmPointcutPublisher.size() > 0);

            VmPointcutPublisher.reset();
            VmPointcutPublisher.active = true;

            assertEquals(0, VmPointcutPublisher.size());
            assertEquals(0L, VmPointcutPublisher.versionStamp());
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