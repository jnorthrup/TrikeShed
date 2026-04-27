     1|package borg.trikeshed.couch.instrument
     2|
     3|import borg.trikeshed.couch.handle.CollectionHandle
     4|import borg.trikeshed.couch.handle.HandleState
     5|import borg.trikeshed.miniduck.MiniRowVec
     6|import borg.trikeshed.lib.Series
     7|import java.util.concurrent.locks.ReentrantLock
     8|
     9|/**
    10| * Thin InstrumentedHandle used by tests to record probes around handle operations.
    11| */
    12|class InstrumentedHandle(val probes: Probes) {
    13|   val underlying: CollectionHandle = CollectionHandle.open()
    14|   val lock = ReentrantLock()
    15|   val inFlight = java.util.concurrent.atomic.AtomicLong(0)
    16|
    17|    fun append(row: MiniRowVec) {
    18|        inFlight.incrementAndGet()
    19|        // give other threads a chance to increment inFlight so we can detect overlap
    20|        Thread.yield()
    21|        val here = inFlight.get()
    22|        if (here > 1) probes.contentionCount.incrementAndGet()
    23|
    24|        // fast-path tryLock to detect contention
    25|        val acquiredImmediately = lock.tryLock()
    26|        if (!acquiredImmediately) {
    27|            probes.contentionCount.incrementAndGet()
    28|            lock.lock()
    29|        }
    30|        try {
    31|            // If we acquired immediately but other threads are queued, count that as contention too
    32|            if (acquiredImmediately && lock.hasQueuedThreads()) probes.contentionCount.incrementAndGet()
    33|            underlying.append(row)
    34|            probes.mutationCount.incrementAndGet()
    35|        } finally {
    36|            lock.unlock()
    37|            inFlight.decrementAndGet()
    38|        }
    39|    }
    40|
    41|    fun snapshot(): Series<MiniRowVec> {
    42|        val snap = underlying.snapshot()
    43|        probes.snapshotCount.incrementAndGet()
    44|        if (underlying.state == HandleState.SEALED) probes.readCount.incrementAndGet()
    45|        return snap
    46|    }
    47|
    48|    fun seal() {
    49|        underlying.seal()
    50|        probes.sealCount.incrementAndGet()
    51|    }
    52|
    53|    fun close() {
    54|        underlying.close()
    55|    }
    56|
    57|    val rowCount: Int get() = underlying.rowCount
    58|}
    59|