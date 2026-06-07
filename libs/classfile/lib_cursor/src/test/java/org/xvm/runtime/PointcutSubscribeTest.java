package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: subscribe / unsubscribe API and drain behaviour.
 */
public class PointcutSubscribeTest {

    @org.junit.jupiter.api.Test
    public void emptyDrain_returnsZero() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(0, evts.length);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void subscribe_receivesEventOnRevise() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            final int[] count = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> count[0]++);

            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x34, "Sub.test", 1);
            long t1 = System.nanoTime();
            // drain shows publish worked
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1);

            // subscriber only fires on revise, not on publish
            assertEquals(0, count[0], "subscriber should not fire on publish (only revise)");

            // Now revise — subscriber fires
            long t2 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent revised = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x35, 2, "Sub.test_revised");
            VmPointcutPublisher.revise(0, revised);
            long t3 = System.nanoTime();
            assertEquals(1, count[0], "subscriber should fire on revise");
            assertTrue(revised.nano >= t2 && revised.nano <= t3);

            VmPointcutPublisher.unsubscribe(subId);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void unsubscribe_stopsReviseEvents() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x34, "Unsub.test", 1);
            long t1 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1);

            final int[] count = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> count[0]++);

            // First revise — fires
            long t2 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent rev1 = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x35, 2, "Unsub.test_rev1");
            VmPointcutPublisher.revise(0, rev1);
            long t3 = System.nanoTime();
            assertEquals(1, count[0]);
            assertTrue(rev1.nano >= t2 && rev1.nano <= t3);

            VmPointcutPublisher.unsubscribe(subId);

            // Second revise — should not fire
            long t4 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent rev2 = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x36, 3, "Unsub.test_rev2");
            VmPointcutPublisher.revise(0, rev2);
            long t5 = System.nanoTime();
            assertEquals(1, count[0], "unsubscribed should not receive more revise events");
            assertTrue(rev2.nano >= t4 && rev2.nano <= t5);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void multipleSubscribers_allReceiveRevise() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x38, "Multi.test", 1);
            long t1 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(1, evts.length);
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1);

            final int[] a = {0};
            final int[] b = {0};
            int idA = VmPointcutPublisher.subscribe(evt -> a[0]++);
            int idB = VmPointcutPublisher.subscribe(evt -> b[0]++);

            // Revise — both subscribers fire
            long t2 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent rev = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x39, 2, "Multi.test_revised");
            VmPointcutPublisher.revise(0, rev);
            long t3 = System.nanoTime();

            assertEquals(1, a[0], "subscriber A should receive revise");
            assertEquals(1, b[0], "subscriber B should receive revise");
            assertTrue(rev.nano >= t2 && rev.nano <= t3);

            VmPointcutPublisher.unsubscribe(idA);

            // Second revise — only B receives
            long t4 = System.nanoTime();
            VmPointcutPublisher.PointcutEvent rev2 = new VmPointcutPublisher.PointcutEvent(
                    evts[0].seq, System.nanoTime(), 0x3A, 3, "Multi.test_revised2");
            VmPointcutPublisher.revise(0, rev2);
            long t5 = System.nanoTime();

            assertEquals(1, a[0], "unsubscribed A should not receive second revise");
            assertEquals(2, b[0], "active B should receive second revise");
            assertTrue(rev2.nano >= t4 && rev2.nano <= t5);

            VmPointcutPublisher.unsubscribe(idB);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void subscribe_returnsUniqueId() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            int id1 = VmPointcutPublisher.subscribe(evt -> {});
            int id2 = VmPointcutPublisher.subscribe(evt -> {});
            int id3 = VmPointcutPublisher.subscribe(evt -> {});

            assertTrue(id1 != id2, "subscribe ids should be unique");
            assertTrue(id2 != id3, "subscribe ids should be unique");

            VmPointcutPublisher.unsubscribe(id1);
            VmPointcutPublisher.unsubscribe(id2);
            VmPointcutPublisher.unsubscribe(id3);
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    @org.junit.jupiter.api.Test
    public void drainAfterSubscribe_sameEvents() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            long t0 = System.nanoTime();
            VmPointcutPublisher.publish(0x38, "DrainSub.test", 1);
            VmPointcutPublisher.publish(0x39, "DrainSub.test2", 2);
            long t1 = System.nanoTime();

            final int[] subCount = {0};
            int subId = VmPointcutPublisher.subscribe(evt -> subCount[0]++);

            // drain does not trigger subscribe callbacks (drain is observation, not notification)
            VmPointcutPublisher.PointcutEvent[] evts = drainAll();
            assertEquals(2, evts.length);
            assertEquals(0, subCount[0], "drain should not trigger subscribers");
            assertTrue(evts[0].nano >= t0 && evts[0].nano <= t1);
            assertTrue(evts[1].nano >= t0 && evts[1].nano <= t1);

            VmPointcutPublisher.unsubscribe(subId);
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