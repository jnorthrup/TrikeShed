package borg.trikeshed.cas

import borg.trikeshed.cas.PostingLifecycle.Limits
import borg.trikeshed.context.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Evoke's maintenance worker as a CCEK element: the intake, the budgeted tick, exact reads on the spine, drain. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostingLifecycleElementTest {
    val small = Limits(
        l0MaxRecords = 8, l0MaxPages = 1_000_000, l0RotationRecords = 4, l0RotationPages = 1_000_000, manifestMaxSegments = 6,
        maxExtentsPerTerm = 5, compactionMaxInputSegments = 3, compactionTierWidth = 3, extentPressure = 3, foldTargetTailExtents = 1,
        reclaimRetiredRangeThreshold = 2, reclaimActionBlockBudget = 1, lowDebtIntervalMs = Long.MAX_VALUE / 4,
    )
    val corpus = listOf("the cursor algebra joins series", "cursor cursor cursor", "an algebra of series and joins", "nothing relevant here", "join the cursor to the algebra")

    /** A fresh TermPostings over the live rows: what every read must equal, best first, ties in admission (slot) order. */
    fun oracle(live: Map<Any?, String>, query: String): List<Pair<Any?, Double>> {
        val postings = TermPostings()
        for ((k, v) in live) postings.upsert(k, v)
        val scores = postings.scores(query)
        return scores.entries.sortedWith(compareByDescending<Map.Entry<Any?, Double>> { it.value }.thenBy { live.keys.indexOf(it.key) }).map { it.key to it.value }
    }

    fun List<PostingHit>.pairs() = map { it.key to it.score }

    @Test fun writesThroughTheIntakeReadExactlyAndARefusedWriteFailsOnlyItself() = runTest {
        val element = PostingLifecycleElement(limits = small, clock = { testScheduler.currentTime }).also { it.open() }
        try {
            val live = LinkedHashMap<Any?, String>()
            corpus.forEachIndexed { i, text -> element.upsert("docs", i, text); live[i] = text }
            assertEquals(oracle(live, "cursor algebra"), element.query("docs", "cursor algebra").pairs())
            assertEquals(oracle(live, "cursor").take(2), element.query("docs", "cursor", 2).pairs())
            assertTrue(element.retire("docs", 1)); live.remove(1)
            assertEquals(oracle(live, "cursor"), element.query("docs", "cursor").pairs())
            // 5 upserts + 1 retire: the first 4 records rotated into the pending frontier; the active one takes 8 more, then refuses
            repeat(6) { element.upsert("docs", 100 + it, "filler $it"); live[100 + it] = "filler $it" }
            val refused = assertFailsWith<IllegalStateException> { element.upsert("docs", 999, "overflow") }
            assertTrue("hard frontier" in refused.message!!, refused.message)
            // six equal scores: the tie order is the slots', i.e. admission order
            assertEquals(oracle(live, "filler"), element.query("docs", "filler").pairs(), "the worker lives on after a refused write")
            assertEquals("pending_l0_sealed", element.maintain("docs").reason)
            element.upsert("docs", 999, "overflow"); live[999] = "overflow"
            assertEquals(oracle(live, "overflow filler cursor"), element.query("docs", "overflow filler cursor").pairs())
            assertFailsWith<NoSuchElementException> { element.query("nothing", "x") }
            assertEquals(live.size, element.status("docs")["docs"])
            withContext(element) { assertSame(element, currentCoroutineContext()[PostingLifecycleElement.Key]) }
        } finally { element.drain() }
    }

    @Test fun aTickTakesAtMostTheActionBudgetAndStopsWhenNothingIsDue() = runTest {
        val element = PostingLifecycleElement(limits = small, intervalMs = 1_000, clock = { testScheduler.currentTime }).also { it.open() }
        try {
            // 300 indexes, each with a pending frontier to seal: one strict action each
            for (i in 1..300) repeat(5) { r -> element.upsert("index-$i", r, "word $r") }
            assertEquals(0, element.lastBurstActions, "the tick at open found nothing due")
            advanceTimeBy(1_000); runCurrent()
            assertEquals(256, element.lastBurstActions, "Evoke's 256 actions per launch")
            advanceTimeBy(1_000); runCurrent()
            assertEquals(44, element.lastBurstActions)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(0, element.lastBurstActions, "nothing due: the launch takes no action and the ticker goes on")
            assertEquals(4L, element.bursts)
            assertEquals(emptyList(), element.maintainDue(2))
        } finally { element.drain() }
    }

    @Test fun aTickAlsoStopsAtItsTimeBudget() = runTest {
        var now = 0L
        // every clock reading, the lifecycles' included, is 100 ms later: a 1 s budget cannot cover ten actions
        val element = PostingLifecycleElement(limits = small, clock = { now += 100; now }, actionsPerTick = 256, tickBudgetMs = 1_000).also { it.open() }
        try {
            for (i in 1..10) repeat(5) { r -> element.upsert("index-$i", r, "word $r") }
            advanceTimeBy(1_000); runCurrent()
            val first = element.lastBurstActions
            assertTrue(first in 1 until 10, "the time budget stopped the launch after $first actions")
            var total = first
            repeat(10) { advanceTimeBy(1_000); runCurrent(); total += element.lastBurstActions }
            assertEquals(10, total, "later launches finish the rest")
        } finally { element.drain() }
    }

    @Test fun readsAndMaintenanceOnTheSpineAreExactInEitherOrder() = runTest {
        val element = PostingLifecycleElement(limits = small, clock = { testScheduler.currentTime }).also { it.open() }
        try {
            val live = LinkedHashMap<Any?, String>()
            corpus.forEachIndexed { i, text -> element.upsert("docs", i, text); live[i] = text }
            val expected = oracle(live, "cursor algebra")
            // a read queued before a seal, and after one: both exact, the seal invisible to the reader
            val read = PostingIntake.Query("docs", "cursor algebra", -1)
            val seal = PostingIntake.Maintain("docs")
            element.intake.send(read); element.intake.send(seal)
            assertEquals(expected, read.reply.await().pairs())
            assertEquals("pending_l0_sealed", seal.reply.await().reason)
            corpus.forEachIndexed { i, text -> element.upsert("docs", 10 + i, text); live[10 + i] = text }
            val again = oracle(live, "cursor algebra")
            val seal2 = PostingIntake.Maintain("docs")
            val read2 = PostingIntake.Query("docs", "cursor algebra", -1)
            element.intake.send(seal2); element.intake.send(read2)
            assertEquals("pending_l0_sealed", seal2.reply.await().reason)
            assertEquals(again, read2.reply.await().pairs())
            // maintain_due ranks strict work first and names the index
            element.upsert("idle", 1, "one")
            repeat(5) { element.upsert("busy", it, "w$it") }
            assertEquals(listOf("busy"), element.maintainDue(1).map { it.a })
        } finally { element.drain() }
    }

    @Test fun drainFinishesAdmittedWorkStopsTheTickerAndRefusesAfter() = runTest {
        val element = PostingLifecycleElement(limits = small, intervalMs = 1_000, clock = { testScheduler.currentTime }).also { it.open() }
        // ten admissible writes (4 rotate, 8 fit the active frontier), queued behind the open tick and never awaited
        val replies = (0 until 10).map { i -> PostingIntake.Upsert("docs", i, listOf("w$i")).also { element.intake.send(it) } }
        assertEquals(0L, element.bursts, "nothing ran yet: the worker is behind the test on the one dispatcher")
        element.drain()
        for (r in replies) assertEquals(null, r.reply.getCompletionExceptionOrNull(), "admitted write ${r.key} finished")
        assertEquals(10, element.index("docs").documents)
        assertEquals(ElementState.CLOSED, element.state)
        assertTrue(element.intake.isClosedForSend)
        assertTrue(element.supervisor.isCompleted)
        val bursts = element.bursts
        assertEquals(1L, bursts, "the open tick was answered before the intake closed")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(bursts, element.bursts, "no tick after drain")
        assertFailsWith<IllegalStateException> { element.upsert("docs", 99, "late") }
        element.drain()
    }
}
