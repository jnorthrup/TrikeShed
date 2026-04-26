package borg.trikeshed.couch

import borg.trikeshed.couch.instrument.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/**
 * Red test: Instrumentation — perform concurrent resizes and assert probe
 * counters and contention metrics are recorded.
 *
 * Donor pattern: go-stopper TaskGroup/TaskInfo tree with per-task
 * Started/Done/Error tracking and runtime/trace region annotations.
 * Simplified to Probes interface with atomic counters.
 *
 * Will fail to compile until Probes, InstrumentedHandle, and probe
 * counters exist.
 */
class InstrumentationTest {

    @Test
    fun appendIncrementsMutationCounter() {
        val probes = Probes()
        val handle = InstrumentedHandle(probes)

        val doc = borg.trikeshed.miniduck.DocRowVec(
            listOf("x"), listOf(1)
        )
        handle.append(doc)
        assertEquals(1, probes.mutationCount.get())
    }

    @Test
    fun snapshotIncrementsSnapshotCounter() {
        val probes = Probes()
        val handle = InstrumentedHandle(probes)

        val doc = borg.trikeshed.miniduck.DocRowVec(
            listOf("x"), listOf(1)
        )
        handle.append(doc)
        handle.snapshot()
        assertEquals(1, probes.snapshotCount.get())
    }

    @Test
    fun sealIncrementsSealCounter() {
        val probes = Probes()
        val handle = InstrumentedHandle(probes)
        handle.seal()
        assertEquals(1, probes.sealCount.get())
    }

    @Test
    fun concurrentAppendsRecordContention() {
        val probes = Probes()
        val handle = InstrumentedHandle(probes)

        val threads = (1..10).map {
            Thread {
                val doc = borg.trikeshed.miniduck.DocRowVec(
                    listOf("i"), listOf(it)
                )
                handle.append(doc)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(
            probes.contentionCount.get() > 0,
            "concurrent appends should record contention; got ${probes.contentionCount.get()}"
        )
        assertEquals(10, probes.mutationCount.get())
    }

    @Test
    fun probesStartAtZero() {
        val probes = Probes()
        assertEquals(0, probes.mutationCount.get())
        assertEquals(0, probes.snapshotCount.get())
        assertEquals(0, probes.sealCount.get())
        assertEquals(0, probes.contentionCount.get())
        assertEquals(0, probes.readCount.get())
    }

    @Test
    fun readIncrementsReadCounter() {
        val probes = Probes()
        val handle = InstrumentedHandle(probes)

        handle.append(
            borg.trikeshed.miniduck.DocRowVec(listOf("x"), listOf(1))
        )
        handle.seal()
        handle.snapshot() // snapshot after seal counts as read
        assertEquals(1, probes.readCount.get())
    }
}
