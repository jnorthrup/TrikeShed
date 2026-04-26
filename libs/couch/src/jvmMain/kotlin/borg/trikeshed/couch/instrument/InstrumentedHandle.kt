package borg.trikeshed.couch.instrument

import borg.trikeshed.couch.handle.CollectionHandle
import borg.trikeshed.couch.handle.HandleState
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.lib.Series
import java.util.concurrent.locks.ReentrantLock

/**
 * Thin InstrumentedHandle used by tests to record probes around handle operations.
 */
class InstrumentedHandle(private val probes: Probes) {
    private val underlying: CollectionHandle = CollectionHandle.open()
    private val lock = ReentrantLock()
    private val inFlight = java.util.concurrent.atomic.AtomicLong(0)

    fun append(row: MiniRowVec) {
        inFlight.incrementAndGet()
        // give other threads a chance to increment inFlight so we can detect overlap
        Thread.yield()
        val here = inFlight.get()
        if (here > 1) probes.contentionCount.incrementAndGet()

        // fast-path tryLock to detect contention
        val acquiredImmediately = lock.tryLock()
        if (!acquiredImmediately) {
            probes.contentionCount.incrementAndGet()
            lock.lock()
        }
        try {
            // If we acquired immediately but other threads are queued, count that as contention too
            if (acquiredImmediately && lock.hasQueuedThreads()) probes.contentionCount.incrementAndGet()
            underlying.append(row)
            probes.mutationCount.incrementAndGet()
        } finally {
            lock.unlock()
            inFlight.decrementAndGet()
        }
    }

    fun snapshot(): Series<MiniRowVec> {
        val snap = underlying.snapshot()
        probes.snapshotCount.incrementAndGet()
        if (underlying.state == HandleState.SEALED) probes.readCount.incrementAndGet()
        return snap
    }

    fun seal() {
        underlying.seal()
        probes.sealCount.incrementAndGet()
    }

    fun close() {
        underlying.close()
    }

    val rowCount: Int get() = underlying.rowCount
}
