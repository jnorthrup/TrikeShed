package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Notification verification for VM and Field pointcut observation paths.
 *
 * onBatch now carries only (source, count, epoch) — no wireproto.
 * Wireproto encode/decode is tested separately via drainToWireproto/fromWireproto.
 */
public class PointcutObservationTest {

    @Test
    public void vmDrain_notifiesObserver() {
        VmPointcutPublisher.reset();
        FieldSynapse.reset();

        var batches = new ArrayList<BatchRecord>();
        int sinkId = PointcutObservation.subscribe((source, count, epoch) ->
                batches.add(new BatchRecord(source, count, epoch)));
        VmPointcutPublisher.active = true;

        try {
            VmPointcutPublisher.publish(0x10, "Test.run", 11);
            VmPointcutPublisher.publish(0x4C, "Test.run", 12);

            VmPointcutPublisher.drain(evt -> {});

            assertEquals(1, batches.size(), "drain should notify once");
            var batch = batches.getFirst();
            assertEquals(PointcutObservation.Source.VM, batch.source);
            assertEquals(2, batch.count);
            assertEquals(0L, batch.epoch);
        } finally {
            PointcutObservation.unsubscribe(sinkId);
            VmPointcutPublisher.reset();
            FieldSynapse.reset();
        }
    }

    @Test
    public void fieldFlush_notifiesObserver() {
        VmPointcutPublisher.reset();
        FieldSynapse.reset();

        var batches = new ArrayList<BatchRecord>();
        int sinkId = PointcutObservation.subscribe((source, count, epoch) ->
                batches.add(new BatchRecord(source, count, epoch)));
        FieldSynapse.active = true;

        try {
            FieldSynapse.publishStatic(0xA5, "Field.read", 21, false);
            FieldSynapse.publishStatic(0xA8, "Field.write", 22, true);

            FieldSynapse.flush("test");

            assertEquals(1, batches.size(), "flush should notify once");
            var batch = batches.getFirst();
            assertEquals(PointcutObservation.Source.FIELD, batch.source);
            assertEquals(2, batch.count);
            assertEquals(0L, batch.epoch, "first slab epoch");
        } finally {
            PointcutObservation.unsubscribe(sinkId);
            VmPointcutPublisher.reset();
            FieldSynapse.reset();
        }
    }

    @Test
    public void unsubscribe_preventsOnBatchDelivery() {
        VmPointcutPublisher.reset();
        FieldSynapse.reset();

        var batches = new ArrayList<BatchRecord>();
        int sinkId = PointcutObservation.subscribe((source, count, epoch) ->
                batches.add(new BatchRecord(source, count, epoch)));
        VmPointcutPublisher.active = true;

        try {
            PointcutObservation.unsubscribe(sinkId);

            VmPointcutPublisher.publish(0x10, "Test.run", 11);
            VmPointcutPublisher.drain(evt -> {});

            assertEquals(0, batches.size());
        } finally {
            VmPointcutPublisher.reset();
            FieldSynapse.reset();
        }
    }

    private record BatchRecord(PointcutObservation.Source source, int count, long epoch) {}
}
