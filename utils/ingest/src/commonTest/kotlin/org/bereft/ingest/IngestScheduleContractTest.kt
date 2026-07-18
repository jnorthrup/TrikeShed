package org.bereft.ingest

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestScheduleContractTest {

    /** Fake scheduler: emits one synthetic IngestResult per source. */
    private class FakeSchedule : IngestSchedule {
        val scheduled = mutableListOf<String>()
        override fun schedule(
            source: String,
            projections: Set<IngestProjection>,
        ): Channel<IngestResult> {
            scheduled.add(source)
            val ch = Channel<IngestResult>(Channel.BUFFERED)
            val result = IngestResult(
                sourcePath = source,
                mediaType = "text/plain",
                detectedFormat = "text",
                extractedContent = charSeries("content-of-$source"),
                projections = projections,
            )
            ch.trySend(result)
            ch.close()
            return ch
        }
    }

    @Test
    fun `schedule returns a channel that receives an IngestResult`() = runTest {
        val sched = FakeSchedule()
        val ch = sched.schedule("/a.txt", IngestProjection.TEXT_ONLY)
        val r = ch.receive()
        assertEquals("/a.txt", r.sourcePath)
        assertEquals("text/plain", r.mediaType)
        assertTrue(r.contentSize > 0)
    }

    @Test
    fun `scheduleBatch merges multiple sources via fan-in`() = runTest {
        val sched = FakeSchedule()
        val srcList = listOf("/a", "/b", "/c")
        val sources: Series<String> = srcList.size j { i -> srcList[i] }
        // run() is non-suspend; it launches per-source coroutines into this
        // test scope. Drain after yielding to let them produce.
        val merged = IngestScheduleMerge.run(this, sched, sources, IngestProjection.TEXT_ONLY)
        // Let the launched fan-in coroutines produce.
        repeat(3) { yield() }
        val got = mutableListOf<String>()
        for (r in merged) got.add(r.sourcePath)
        assertEquals(3, got.size)
        assertEquals(srcList, sched.scheduled.sorted())
    }
}
