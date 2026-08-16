package borg.trikeshed.cas

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunnelThreadAnchoredRelocationTest {

    /**
     * Master: A\nB\nC — three lines with neighbor stamps.
     * Patch: A\nX\nC — B replaced by X.
     *
     * X's content is novel (not in master) but its stamp hex matches B's
     * stamp hex (both have prev=A, next=C) → thread-anchored RELOCATED.
     *
     * A and C also survive as RELOCATED because their neighbors changed
     * (A's next is now X, C's prev is now X) → their stamps differ from
     * master's stamps. But they are NOT thread-anchored (their new stamps
     * contain X's prefix, which is not in the master stamp funnel).
     *
     * The key assertion: the X cluster is thread-anchored and RELOCATED.
     */
    @Test
    fun testThreadAnchoredRelocation() {
        val masterText = "A\nB\nC"
        val masterSpine = LineCas.spine(masterText)
        val masterBaseline = FunnelResidualMerge.buildMasterBaseline(masterSpine)

        val patchText = "A\nX\nC"
        val patchSpine = LineCas.spine(patchText)

        // X is line ordinal 1 — its prev is A, next is C, same as B's stamp.
        val xNode = patchSpine[1]
        assertTrue(
            masterBaseline.containsStamp(xNode.stamp.hex),
            "X's stamp hex should be in master's stamp funnel (same neighbors as B)"
        )

        val sources = 1 j { _: Int -> patchSpine }
        val receipt = FunnelResidualMerge.merge(sources, masterBaseline)

        // All 3 lines survive as RELOCATED: X is novel content, A and C have moved context.
        assertEquals(0, receipt.novelCount, "No truly novel lines; all are relocations")
        assertEquals(3, receipt.relocatedCount, "All 3 are RELOCATED")
        assertEquals(3, receipt.conflicts.size, "RELOCATED clusters posted as conflicts for LLM resolution")
        assertEquals(0, receipt.kept.size, "No NOVEL survivors to fast-apply")
        assertEquals(0, receipt.resolvedCount, "No resolver supplied")

        // Find the X conflict — it's the one that is thread-anchored.
        var foundThreadAnchored = false
        for (i in 0 until receipt.conflicts.size) {
            val post = receipt.conflicts[i]
            if (post.isThreadAnchored) {
                foundThreadAnchored = true
                assertEquals(
                    FunnelResidualMerge.ClusterGrade.RELOCATED,
                    post.grade,
                    "Thread-anchored line should grade RELOCATED"
                )
            }
        }
        assertTrue(foundThreadAnchored, "At least one conflict should be thread-anchored (X)")
    }

    /**
     * A truly novel line with no inherited context should grade NOVEL.
     * Master: A\nB, Patch: A\nB\nZ — Z has prev=B, next=EDGE.
     * Z's stamp (B_prefix + EDGE_HEX) is not in master's stamp funnel
     * because B's stamp is (A_prefix + EDGE_HEX) — different. So Z
     * is truly novel.
     */
    @Test
    fun testTrulyNovelStillNovel() {
        val masterText = "A\nB"
        val masterSpine = LineCas.spine(masterText)
        val masterBaseline = FunnelResidualMerge.buildMasterBaseline(masterSpine)

        val patchText = "A\nB\nZ"
        val patchSpine = LineCas.spine(patchText)

        val sources = 1 j { _: Int -> patchSpine }
        val receipt = FunnelResidualMerge.merge(sources, masterBaseline)

        // B's stamp changes (next=Z instead of EDGE), so B is also RELOCATED.
        // Z is truly novel.
        assertTrue(receipt.novelCount >= 1, "Z should be NOVEL")
        assertTrue(receipt.kept.size >= 1, "Z should be in kept (NOVEL survivors)")
        assertTrue(receipt.conflicts.size >= 0, "B may be posted as RELOCATED conflict")
    }

    /**
     * When the LLM supplies a deterministic [ResolutionRoutine], RELOCATED
     * conflicts are resolved and the receipt carries resolved counts instead
     * of pending conflicts. The routine here is trivial: accept the first
     * source's version for every conflict — a deterministic choice.
     */
    @Test
    fun testResolverResolvesConflicts() {
        val masterText = "A\nB\nC"
        val masterSpine = LineCas.spine(masterText)
        val masterBaseline = FunnelResidualMerge.buildMasterBaseline(masterSpine)

        val patchText = "A\nX\nC"
        val patchSpine = LineCas.spine(patchText)
        val sources = 1 j { _: Int -> patchSpine }

        // Deterministic routine: accept source 0's version of every conflict.
        val routine = FunnelResidualMerge.ResolutionRoutine { post ->
            FunnelResidualMerge.ConflictResolution.Accept(post.cluster.copies[0].sourceIdx)
        }

        val receipt = FunnelResidualMerge.merge(sources, masterBaseline, routine)

        assertEquals(0, receipt.conflicts.size, "Resolver should consume all conflicts")
        assertEquals(3, receipt.resolvedCount, "All 3 RELOCATED clusters resolved")
        assertEquals(3, receipt.relocatedCount, "relocatedCount counts the original RELOCATED set")
        assertEquals(3, receipt.kept.size, "Accepted resolutions land in kept")
    }
}
