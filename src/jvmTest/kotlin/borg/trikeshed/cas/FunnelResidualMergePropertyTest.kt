package borg.trikeshed.cas

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Determinism properties for [FunnelResidualMerge]: the receipt pipeline
 * (merge) is a pure function of the SET of sources — permutation-invariant —
 * and the degenerate shapes (zero sources, empty texts, identical inputs,
 * single input, empty master) all land where the stage contracts say.
 *
 * The pijulMerge TEXT path is covered too; its two broken laws stay in this
 * file under @Ignore with the code-level cause (they compose [borg.trikeshed.crdt.PijulCrdt],
 * whose offset-addressed apply is order-sensitive — see PijulCrdtPropertyTest).
 */
class FunnelResidualMergePropertyTest {

    private fun masterLines(n: Int) = (0 until n).map { "L${it.toString().padStart(2, '0')}" }

    private fun baselineOf(text: String) =
        FunnelResidualMerge.buildMasterBaseline(LineCas.spine(text))

    /** Scalar receipt shape, in declaration order, for one-shot equality diffs. */
    private fun counts(r: FunnelResidualMerge.MergeReceipt): List<Int> = listOf(
        r.novelCount, r.relocatedCount, r.inheritedCount, r.inheritedCrossCount,
        r.resolvedCount, r.kept.size, r.dropped.size, r.conflicts.size,
    )

    /**
     * Order-free receipt fingerprint: every cluster (kept, dropped, and posted
     * conflicts) as grade:mini64:copyCount, sorted. SourceIdx is deliberately
     * excluded — it is the one thing a permutation necessarily relabels.
     */
    private fun fingerprint(r: FunnelResidualMerge.MergeReceipt): List<String> {
        val out = ArrayList<String>(r.kept.size + r.dropped.size + r.conflicts.size)
        for (i in 0 until r.kept.size) {
            val gc = r.kept[i]
            out.add("${gc.grade}:${gc.cluster.mini64.raw}:${gc.cluster.copies.size}")
        }
        for (i in 0 until r.dropped.size) {
            val gc = r.dropped[i]
            out.add("${gc.grade}:${gc.cluster.mini64.raw}:${gc.cluster.copies.size}")
        }
        for (i in 0 until r.conflicts.size) {
            val p = r.conflicts[i]
            out.add("${p.grade}:${p.cluster.mini64.raw}:${p.cluster.copies.size}")
        }
        return out.sorted()
    }

    /**
     * Determinism law: merge() receipts are invariant under any permutation of
     * the source order — same counts, same cluster multiset. Source order only
     * relabels SourceIdx, which grading never consults (INHERITED_CROSS checks
     * stamp equality over ALL copies; the RELOCATED master-stamp check is
     * order-safe because residualsOf already dropped every stamp-equal atom).
     */
    @Test
    fun mergeReceiptInvariantUnderSourcePermutation() {
        val rnd = Random(42)
        val master = masterLines(40)
        val baseline = baselineOf(master.joinToString("\n"))

        // Seeded generator over disjoint ordinal bands: two unique novel
        // inserts, one novel line shared by two sources, one relocation
        // (57-way idiom), one source identical to master.
        val novelAAt = rnd.nextInt(2, 12)
        val novelBAt = rnd.nextInt(14, 24)
        val sharedAt = rnd.nextInt(26, 36)
        val sources = listOf(
            master.toMutableList().apply { add(novelAAt, "NOVEL_A") },
            master.toMutableList().apply { add(novelBAt, "NOVEL_B") },
            master.toMutableList().apply { add(sharedAt, "NOVEL_SHARED") },
            master.toMutableList().apply { add(sharedAt, "NOVEL_SHARED") },
            master.toMutableList().apply { add(5, removeAt(30)) },
            master.toMutableList(),
        ).map { LineCas.spine(it.joinToString("\n")) }

        val perms = listOf(
            sources.indices.toList(),
            sources.indices.reversed().toList(),
            sources.indices.shuffled(Random(42)),
            sources.indices.shuffled(Random(1337)),
        )
        val receipts = perms.map { perm ->
            FunnelResidualMerge.merge(perm.size j { i: Int -> sources[perm[i]] }, baseline)
        }

        val first = receipts.first()
        for (r in receipts.drop(1)) {
            assertEquals(counts(first), counts(r), "receipt counts must be permutation-invariant")
            assertEquals(fingerprint(first), fingerprint(r), "cluster multiset must be permutation-invariant")
        }
        assertTrue(first.novelCount >= 2, "the two unique novel lines survive as NOVEL")
        assertTrue(first.inheritedCrossCount >= 1, "the shared novel line collapses to INHERITED_CROSS")
        assertTrue(first.relocatedCount >= 1, "the relocation surfaces as RELOCATED")
    }

    /** Degenerate: zero sources → empty topology, all-zero receipt. */
    @Test
    fun zeroSourcesYieldEmptyReceipt() {
        val receipt = FunnelResidualMerge.merge(emptySeriesOf(), baselineOf("A\nB\nC"))
        assertEquals(List(8) { 0 }, counts(receipt))
    }

    /** Degenerate: N sources identical to master — every atom is a strict funnel+stamp hit, nothing residual. */
    @Test
    fun identicalToMasterSourcesYieldEmptyReceipt() {
        val masterText = masterLines(20).joinToString("\n")
        val receipt = FunnelResidualMerge.merge(
            5 j { _: Int -> LineCas.spine(masterText) },
            baselineOf(masterText),
        )
        assertEquals(List(8) { 0 }, counts(receipt))
    }

    /** Degenerate: empty / whitespace-only source texts → empty spines, empty receipt. */
    @Test
    fun emptySourcesYieldEmptyReceipt() {
        val texts = listOf("", "\n\n", "   \n\t\n")
        val receipt = FunnelResidualMerge.merge(
            texts.size j { i: Int -> LineCas.spine(texts[i]) },
            baselineOf("A\nB"),
        )
        assertEquals(List(8) { 0 }, counts(receipt))
    }

    /**
     * Degenerate: single source, no resolver. Receipt bookkeeping identity —
     * kept = NOVEL, conflicts = RELOCATED, dropped = INHERITED + INHERITED_CROSS,
     * nothing resolved.
     */
    @Test
    fun singleSourceReceiptBookkeeping() {
        val master = masterLines(12)
        val edited = master.toMutableList().apply { add(6, "NOVEL_ONLY") }
        val receipt = FunnelResidualMerge.merge(
            1 j { _: Int -> LineCas.spine(edited.joinToString("\n")) },
            baselineOf(master.joinToString("\n")),
        )
        assertEquals(receipt.novelCount, receipt.kept.size)
        assertEquals(receipt.relocatedCount, receipt.conflicts.size)
        assertEquals(receipt.inheritedCount + receipt.inheritedCrossCount, receipt.dropped.size)
        assertEquals(0, receipt.resolvedCount)
        assertTrue(receipt.novelCount >= 1, "NOVEL_ONLY is a singleton funnel miss with no inherited context")
    }

    /**
     * Degenerate: N identical copies of ONE divergent source — the shared novel
     * content is cross-source boilerplate: every cluster carries N copies with
     * equal stamps, so nothing can grade NOVEL (singleton-only) and the novel
     * line lands INHERITED_CROSS, the DRY harvest.
     */
    @Test
    fun identicalDivergentSourcesCollapseToInheritedCross() {
        val master = masterLines(20)
        val editedText = master.toMutableList().apply { add(10, "NOVEL_SHARED") }.joinToString("\n")
        val receipt = FunnelResidualMerge.merge(
            4 j { _: Int -> LineCas.spine(editedText) },
            baselineOf(master.joinToString("\n")),
        )
        assertEquals(0, receipt.novelCount, "no singleton clusters — every residual appears in all 4 copies")
        assertTrue(receipt.inheritedCrossCount >= 1, "the shared novel line is INHERITED_CROSS")
        for (i in 0 until receipt.dropped.size) {
            val gc = receipt.dropped[i]
            if (gc.grade == FunnelResidualMerge.ClusterGrade.INHERITED_CROSS) {
                assertEquals(4, gc.cluster.copies.size, "INHERITED_CROSS cluster spans all 4 identical sources")
            }
        }
    }

    /** Degenerate: empty master — every distinct source line is a funnel miss with no inherited context: all NOVEL. */
    @Test
    fun emptyMasterMakesEverythingNovel() {
        val receipt = FunnelResidualMerge.merge(
            1 j { _: Int -> LineCas.spine("ONE\nTWO\nTHREE") },
            baselineOf(""),
        )
        assertEquals(3, receipt.novelCount)
        assertEquals(0, receipt.relocatedCount)
        assertEquals(3, receipt.kept.size)
    }

    /**
     * KNOWN BROKEN — kept as the counterexample record.
     *
     * pijulMerge with a single source IDENTICAL to master contributes zero
     * residual patches, so mergedText should reproduce master line-for-line.
     * It does not, for any master of 4+ lines: the seed patch passes LINE
     * ORDINALS as Change.Insert.pos, but PijulCrdt.findAttachIndex interprets
     * pos as a CHARACTER offset (cumulativeLen accumulates content.length).
     * With 2-char lines the seed's Insert(3, "D\n") binary-searches starts
     * [0,0,2,4] for pos 3 and attaches after B, not C — traced seed render for
     * master A\nB\nC\nD\nE is "A\nB\nD\nE\nC\n": the seed alone scrambles the
     * document. (FunnelPijulParallelMergeTest never catches this because it
     * only asserts substring containment, never line order.)
     */
    @Ignore
    @Test
    fun pijulMergeReproducesMasterWhenNothingDiverges() {
        val masterText = "A\nB\nC\nD\nE"
        val result = FunnelResidualMerge.pijulMerge(
            1 j { _: Int -> LineCas.spine(masterText) },
            baselineOf(masterText),
            masterText,
            1 j { _: Int -> masterText },
        )
        assertEquals("A\nB\nC\nD\nE\n", result.mergedText)
    }

    /**
     * KNOWN DIVERGENT — pijulMerge TEXT under source permutation. The receipt
     * pipeline is permutation-invariant (asserted green above), but pijulMerge
     * feeds each source's residuals to the CRDT sequentially, and offset-
     * addressed CRDT patches do not commute (PijulCrdtPropertyTest), so the
     * merged text depends on source order. Traced for master A\nB\nC with
     * sources A\nX\nB\nC and A\nB\nY\nC: the two orders render
     * "A\nB\nC\nY\nX\nB\nA\nB\nC\n" vs "A\nX\nB\nA\nB\nC\nY\nB\nC\n"
     * (residuals also re-insert the stamp-changed master neighbors, duplicating
     * lines). Unignore after the CRDT anchors patches to stable vertex
     * identities.
     */
    @Ignore
    @Test
    fun pijulMergeTextInvariantUnderSourcePermutation() {
        val masterText = "A\nB\nC"
        val baseline = baselineOf(masterText)
        val texts = listOf("A\nX\nB\nC", "A\nB\nY\nC")
        val fwd = FunnelResidualMerge.pijulMerge(
            2 j { i: Int -> LineCas.spine(texts[i]) }, baseline,
            masterText, 2 j { i: Int -> texts[i] },
        ).mergedText
        val rev = FunnelResidualMerge.pijulMerge(
            2 j { i: Int -> LineCas.spine(texts[1 - i]) }, baseline,
            masterText, 2 j { i: Int -> texts[1 - i] },
        ).mergedText
        assertEquals(fwd, rev, "merged text must not depend on source order")
    }
}
