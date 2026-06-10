package borg.trikeshed.couch

import borg.trikeshed.couch.control.*
import kotlin.test.*

/**
 * Red test: AdmissionControl — throttle producers via tryAcquire/release;
 * verify backpressure (queue does not grow unboundedly).
 *
 * Donor pattern: go-stopper limit.WithMaxConcurrency(limit)
 * uses a buffered channel as counting semaphore with fast-path non-blocking
 * and slow-path blocking that respects stop signals.
 *
 * Will fail to compile until AdmissionControl interface exists.
 */
class AdmissionControlTest {

    @Test
    fun tryAcquireWithinCapacitySucceeds() {
        val ac = AdmissionControl(capacity = 2)
        assertTrue(ac.tryAcquire())
        assertTrue(ac.tryAcquire())
    }

    @Test
    fun tryAcquireOverCapacityFails() {
        val ac = AdmissionControl(capacity = 2)
        ac.tryAcquire()
        ac.tryAcquire()
        assertFalse(ac.tryAcquire(), "third acquire should be rejected")
    }

    @Test
    fun releaseAllowsSubsequentAcquire() {
        val ac = AdmissionControl(capacity = 1)
        assertTrue(ac.tryAcquire())
        assertFalse(ac.tryAcquire())
        ac.release()
        assertTrue(ac.tryAcquire())
    }

    @Test
    fun backpressurePreventsUnboundedGrowth() {
        val ac = AdmissionControl(capacity = 3)
        var accepted = 0
        var rejected = 0
        repeat(10) {
            if (ac.tryAcquire()) accepted++ else rejected++
        }
        assertEquals(3, accepted)
        assertEquals(7, rejected)
    }

    @Test
    fun acquireOnSealedHandleIsRejected() {
        val ac = AdmissionControl(capacity = 5)
        ac.seal()
        assertFalse(ac.tryAcquire(), "acquire after seal should be rejected")
    }

    @Test
    fun acquireOnClosedHandleIsRejected() {
        val ac = AdmissionControl(capacity = 5)
        ac.close()
        assertFalse(ac.tryAcquire(), "acquire after close should be rejected")
    }

    @Test
    fun releaseAfterSealIsStillAllowed() {
        val ac = AdmissionControl(capacity = 1)
        assertTrue(ac.tryAcquire())
        ac.seal()
        // draining in-flight work: release still works
        ac.release()
    }

    @Test
    fun capacityZeroRejectsAll() {
        val ac = AdmissionControl(capacity = 0)
        assertFalse(ac.tryAcquire())
    }

    @Test
    fun concurrentAcquireDoesNotExceedCapacity() {
        val ac = AdmissionControl(capacity = 4)
        val acquired = java.util.concurrent.atomic.AtomicInteger(0)
        val rejected = java.util.concurrent.atomic.AtomicInteger(0)

        val threads = (1..20).map {
            Thread {
                if (ac.tryAcquire()) acquired.incrementAndGet()
                else rejected.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(4, acquired.get())
        assertEquals(16, rejected.get())
    }
}
