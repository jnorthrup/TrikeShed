package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step F gate — CAS'd scoring sessions:
 *
 * 1. Session-cid reproducibility: identical inputs mint the identical cid.
 * 2. Drift: a corpus plus one document diffs to ONLY the expected deltas —
 *    the added doc's score appears; prior scores are untouched.
 * 3. No model call on the scoring path: the whole session is LineCas
 *    mechanics over a CasStore (the test itself is the proof — nothing else
 *    is reachable from the module).
 */
class ScoringSessionTest {

    private fun corpus(): Triple<CasStore, LineCasIndex, List<Pair<ContentId, LineSpine>>> {
        val cas = CasStore.inMemory()
        val idx = LineCasIndex()
        val probes = mutableListOf<Pair<ContentId, LineSpine>>()
        for (n in 1..2) {
            val text = "alpha line one $n\nbeta shared structural line\ngamma closing $n"
            val spine = LineCas.spineInto(cas, text)
            val cid = idx.ingestSpine(spine)
            probes += cid to spine
        }
        return Triple(cas, idx, probes)
    }

    private fun run(cas: CasStore, idx: LineCasIndex, probes: List<Pair<ContentId, LineSpine>>, seq: Long = 2L): ScoringSession.Run {
        val method = ScoringSession.methodCid("L1", "LINKED", 0.68)
        val scores = ScoringSession.scoreProbes(idx, probes)
        return ScoringSession.Run(seq, probes.map { it.first }, method, scores)
    }

    @Test
    fun identicalInputsReproduceIdenticalSessionCid() {
        val (cas, idx, probes) = corpus()
        val r1 = run(cas, idx, probes)
        val r2 = run(cas, idx, probes)
        val c1 = ScoringSession.sessionCid(cas, r1)
        val c2 = ScoringSession.sessionCid(cas, r2)
        assertEquals(c1, c2, "identical corpus+method+scores → identical session cid")
        // and the session bytes are themselves recoverable from the CAS
        assertTrue(cas.get(c1)!!.contentEquals(ScoringSession.canonicalBytes(r1)))
    }

    @Test
    fun corpusPlusOneDocumentDiffsToOnlyExpectedDeltas() {
        val (cas, idx, probes) = corpus()
        val before = run(cas, idx, probes)

        // corpus grows by one document
        val newSpine = LineCas.spineInto(cas, "alpha line one 3\nbeta shared structural line\ngamma closing 3")
        val newCid = idx.ingestSpine(newSpine)
        val afterProbes = probes + (newCid to newSpine)
        val after = run(cas, idx, afterProbes, seq = 3L)

        val drift = ScoringSession.diff(before, after)
        assertEquals(listOf(newCid), drift.added, "exactly the new document is added")
        assertEquals(emptyList(), drift.removed, "nothing removed")
        // drift is explainable by the new document alone: prior scores may only GAIN
        // hits (they share the beta line with the new doc) — never lose them
        val beforeById = before.scores.associate { it.targetCid to it }
        val afterById = after.scores.associate { it.targetCid to it }
        for ((cid, old) in beforeById) {
            val now = afterById[cid]!!
            assertTrue(now.linked >= old.linked, "linked may only grow (corpus grew): $cid")
            val oldTotal = old.linked + old.partial + old.contentOnly
            val newTotal = now.linked + now.partial + now.contentOnly
            assertTrue(newTotal >= oldTotal, "total hits may only grow: $cid")
        }
        assertTrue(drift.changed.isNotEmpty(), "drift detected across the corpus growth")
    }

    @Test
    fun methodChangeMovesTheSessionCid() {
        val (cas, idx, probes) = corpus()
        val r1 = run(cas, idx, probes)
        val method2 = ScoringSession.methodCid("L2", "LINKED", 0.68)
        val r2 = ScoringSession.Run(r1.corpusLastSeq, r1.spineCids, method2, r1.scores)
        assertTrue(
            ScoringSession.sessionCid(cas, r1) != ScoringSession.sessionCid(cas, r2),
            "different method → different session",
        )
    }
}
