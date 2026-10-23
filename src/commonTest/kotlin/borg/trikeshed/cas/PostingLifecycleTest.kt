package borg.trikeshed.cas

import borg.trikeshed.cas.PostingLifecycle.Limits
import kotlin.math.abs
import kotlin.test.*

/** The lifecycle keeps every read exact: a fresh TermPostings over the live rows is the oracle after each action. */
class PostingLifecycleTest {
    private val small = Limits(
        l0MaxRecords = 8, l0MaxPages = 1_000_000, l0RotationRecords = 4, l0RotationPages = 1_000_000,
        manifestMaxSegments = 6, maxExtentsPerTerm = 5, compactionMaxInputSegments = 3, compactionTierWidth = 3,
        extentPressure = 3, foldTargetTailExtents = 1, reclaimRetiredRangeThreshold = 2, reclaimActionBlockBudget = 1,
        lowDebtIntervalMs = 100,
    )

    private fun oracle(live: Map<Any?, String>, query: String): Map<Any?, Double> {
        val p = TermPostings()
        live.forEach { (k, v) -> p.upsert(k, v) }
        return p.scores(query)
    }

    private fun assertExact(index: PostingLifecycle, live: Map<Any?, String>, label: String) {
        for (query in listOf("cursor", "algebra", "cursor algebra", "series join", "nothing")) {
            val expected = oracle(live, query)
            val actual = index.scores(query)
            assertEquals(expected.keys, actual.keys, "$label [$query] keys")
            for ((k, v) in expected) assertTrue(abs(v - actual.getValue(k)) < 1e-12, "$label [$query] $k")
        }
    }

    private val corpus = listOf("the cursor algebra joins series", "cursor cursor cursor", "an algebra of series and joins", "nothing relevant", "join the cursor to the algebra again", "series of cursors")

    @Test fun writesRotateAtTheSoftThresholdAndRefuseAtTheHardLimitWhilePendingIsUnsealed() {
        val index = PostingLifecycle(limits = small)
        val live = LinkedHashMap<Any?, String>()
        for (i in 0 until 4) { index.upsert(i, corpus[i]); live[i] = corpus[i] }
        assertNull(index.pending); assertTrue(index.checkpointDue())
        index.upsert(4, corpus[4]); live[4] = corpus[4]
        assertNotNull(index.pending, "the fifth write rotated inline"); assertEquals(4, index.pending!!.records.size); assertEquals(1, index.active.records.size)
        assertExact(index, live, "two frontiers")
        for (i in 5 until 12) { index.upsert(i, corpus[i % corpus.size]); live[i] = corpus[i % corpus.size] }
        assertEquals(8, index.active.records.size)
        assertFailsWith<IllegalStateException> { index.upsert(99, "overflow") }
        assertExact(index, live, "hard limit")
        val sealed = index.tryMaintain()
        assertEquals("segment_seal", sealed.mode); assertEquals("pending_l0_sealed", sealed.reason); assertTrue(sealed.maintained)
        assertEquals(1, index.segments.size); assertEquals(1L, index.statisticsEpoch)
        index.upsert(99, "overflow"); live[99] = "overflow"
        assertExact(index, live, "after seal")
        assertEquals("evoke_maintenance_result(maintained=true, reason=pending_l0_sealed, mode=segment_seal, compaction_required=false)", sealed.toString())
    }

    @Test fun theSelectorTakesOneActionInTheCOrderAndEveryStateReadsExactly() {
        val index = PostingLifecycle(limits = small)
        val live = LinkedHashMap<Any?, String>()
        var next = 0
        // Writers stop short of the hard frontier while a pending seal is blocked (backpressure).
        fun write(n: Int) { repeat(n) { if (index.pending != null && index.active.records.size + 2 > small.l0MaxRecords) return; val t = corpus[next % corpus.size]; index.upsert(next, t); live[next] = t; next++ } }
        val modes = ArrayList<String>()
        write(5)
        // Drive the index through many ticks; every maintained action leaves the read exact.
        repeat(60) { tick ->
            val r = index.tryMaintain()
            modes += r.mode + if (r.maintained) "" else "!"
            assertExact(index, live, "tick $tick ${r}")
            if (!r.maintained) write(5)
            if (tick % 7 == 3 && live.isNotEmpty()) { val k = live.keys.first(); index.retire(k); live.remove(k) }
        }
        assertTrue("segment_seal" in modes, modes.toString())
        assertTrue("segment_compaction" in modes || modes.any { it.startsWith("term_") }, modes.toString())
        // Status reports the lifecycle keys.
        val status = index.status()
        assertEquals(index.segments.size, status["segment_count"]); assertEquals(live.size, status["docs"])
        assertTrue((status["delta"] as Map<*, *>).containsKey("active"))
    }

    @Test fun compactionFoldsAndReclaimFollowTheirTriggers() {
        val index = PostingLifecycle(limits = small.copy(extentPressure = 3, foldTargetTailExtents = 1, compactionMaxInputSegments = 2))
        val live = LinkedHashMap<Any?, String>()
        var next = 0
        fun seal(n: Int) { repeat(n) { index.upsert(next, "cursor algebra $next"); live[next] = "cursor algebra $next"; next++ }; index.upsert(next, "x"); live[next] = "x"; next++; assertEquals("segment_seal", index.tryMaintain().mode) }
        // Four sealed segments each holding "cursor": extent pressure (3) is reached.
        repeat(4) { seal(4) }
        assertEquals(4, index.segments.size)
        val cursor = index.lexicon.find("cursor")!!.id
        assertTrue(index.extentCount(cursor) >= 3)
        // Frontier work (rotate, seal) precedes structural work in the selector; drain it first.
        while (index.pending != null || index.checkpointDue()) assertTrue(index.tryMaintain().mode.let { it == "segment_rotation" || it == "segment_seal" })
        val first = index.tryMaintain()
        assertTrue(first.maintained, first.toString())
        assertTrue(first.mode == "segment_compaction" || first.mode == "term_structural_fold", first.toString())
        assertExact(index, live, "after $first")
        // Keep maintaining until idle; folds leave at most the target tail of uncovered extents for the term.
        var guard = 0
        while (index.tryMaintain().also { assertExact(index, live, "drain ${it}") }.maintained) check(++guard < 50)
        assertTrue(index.extentCount(cursor) <= 1 || index.selectCompaction() == null)
        // Retired page ranges accrued from replaced objects; a pinned reader fences reclamation.
        if (index.reclamationDue()) {
            index.pin()
            assertEquals("reader_fence_busy", index.tryReclaim().reason)
            index.release()
            val reclaimed = index.tryReclaim()
            assertTrue(reclaimed.maintained); assertEquals("segment_reclamation", reclaimed.mode)
        }
        // Retiring documents stops them scoring and compaction drops them physically.
        index.retire(0); live.remove(0)
        assertExact(index, live, "retired")
    }

    @Test fun lowDebtRotationAndMaintainDueFollowTheClockAndTiers() {
        var now = 0L
        val index = PostingLifecycle(limits = small.copy(l0RotationRecords = 100, l0MaxRecords = 200, lowDebtIntervalMs = 50), clock = { now })
        index.upsert(1, "cursor")
        assertFalse(index.due(), "small debt is not due before the interval")
        now = 60
        assertTrue(index.lowDebtDue()); assertTrue(index.due()); assertFalse(index.strictDue())
        val idle = PostingLifecycle(limits = small)
        val busy = PostingLifecycle(limits = small).also { i -> repeat(5) { k -> i.upsert(k, corpus[k]) } }
        val rows = PostingLifecycle.maintainDue(listOf(index, idle, busy), maxIndexes = 2)
        assertEquals(listOf(2, 0), rows.map { it.first }, "strict-due (busy, pending seal) ranks before low-debt; idle answers nothing")
        assertEquals("segment_seal", rows[0].second.mode); assertEquals("segment_rotation", rows[1].second.mode)
        assertEquals(emptyList(), PostingLifecycle.maintainDue(listOf(idle), 1))
        assertEquals(emptyList(), PostingLifecycle.maintainDue(listOf(busy), 0))
        assertEquals(idle.tryMaintain().toString(), idle.maintain().toString(), "maintain is the same single action for automatic policies")
    }
}
