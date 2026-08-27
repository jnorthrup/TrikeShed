package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.vitals.JvmVitals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step H gate — H1 blackboard wire repairs + H3 heap continent:
 *
 * 1. Replay arrives in SEQUENCE order across a ring wrap (the old code swept
 *    array slots 0..255, which scrambles order after the first wrap).
 * 2. Every SSE event carries `id: <seq>` so `since=` is computable.
 * 3. A reconnect with `since=<seq>` receives exactly the missed events.
 * 4. The snapshot route returns the whole board.
 * 5. Heap histogram parser: jcmd's `num #instances #bytes class` table → rows,
 *    sorted by bytes, Total dropped.
 */
class BlackboardWireRepairTest {

    private class Sink {
        val chunks = mutableListOf<ByteArray>()
        val all: String get() = chunks.map { it.decodeToString() }.joinToString("")
        suspend fun send(b: ByteArray) {
            chunks += b
        }
    }

    @Test
    fun replayIsInSequenceOrderAcrossWrapAndEveryEventCarriesAnId() = runTest {
        val bb = ConfixBlackboard.empty()
        val wire = BlackboardWire(bb, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // push more than the ring capacity: seqs wrap the 256-slot array
        for (i in 0 until 260) {
            bb.put("k$i", "v$i", "test")
        }
        val sink = Sink()
        val job = launch { wire.route("GET", "/blackboard/facts?since=0", "", sink::send) }
        withTimeout(5000) { delay(300) } // allow the collector + replay to flush
        job.cancel()

        val text = sink.all
        val ids = Regex("id: (\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertTrue(ids.size >= 250, "replay should carry most of the ring, got ${ids.size}")
        assertEquals(ids.sorted(), ids, "replay ids must be in ascending sequence order across the wrap")
        assertTrue(ids.first() < ids.last(), "monotonic")
    }

    @Test
    fun reconnectWithSinceReceivesExactlyTheMissedEvents() = runTest {
        val bb = ConfixBlackboard.empty()
        val wire = BlackboardWire(bb, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        for (i in 0 until 10) bb.put("k$i", "v$i", "test")
        withTimeout(5000) { delay(150) }

        val sink = Sink()
        val job = launch { wire.route("GET", "/blackboard/facts?since=7", "", sink::send) }
        withTimeout(5000) { delay(300) }
        job.cancel()

        val ids = Regex("id: (\\d+)").findAll(sink.all).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(7, 8, 9), ids.filter { it < 10 }, "since=7 → exactly seqs 7,8,9 replayed (0-based)")
    }

    @Test
    fun snapshotRouteReturnsTheWholeBoard() = runTest {
        val bb = ConfixBlackboard.empty()
        val wire = BlackboardWire(bb, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        bb.put("pointcut/Foo/bar/1", "a", "test")
        bb.put("pointcut/Foo/bar/2", "b", "test")
        withTimeout(5000) { delay(150) }

        val r = wire.route("GET", "/blackboard/board", "", null)
        assertTrue(r != null && r.status == 200)
        assertTrue(r!!.body.contains("pointcut/Foo/bar/1") && r.body.contains("pointcut/Foo/bar/2"))
    }

    @Test
    fun heapHistogramParserReadsJcmdTable() {
        val sample = """
             num     #instances         #bytes  class name
        ----------------------------------------------
           1:        123456       12345678  byte[]

           2:         54321        4321000  java.lang.String

           3:           999         123456  Total
        """.trimIndent()
        val rows = JvmVitals().parseClassHistogram(sample)
        assertEquals(2, rows.size, "Total row dropped, table parsed")
        assertEquals("byte[]", rows[0].className)
        assertEquals(12345678L, rows[0].bytes)
        assertEquals(54321L, rows[1].count)
        assertTrue(rows[0].bytes >= rows[1].bytes, "sorted descending by bytes")
    }
}
