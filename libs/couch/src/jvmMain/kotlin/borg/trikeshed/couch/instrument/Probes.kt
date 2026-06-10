package borg.trikeshed.couch.instrument

import java.util.concurrent.atomic.AtomicLong

/** Simple probes with atomic counters used by tests. */
class Probes {
    val mutationCount = AtomicLong(0)
    val snapshotCount = AtomicLong(0)
    val sealCount = AtomicLong(0)
    val contentionCount = AtomicLong(0)
    val readCount = AtomicLong(0)
}
