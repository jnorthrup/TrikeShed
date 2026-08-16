package borg.trikeshed.cas

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunnelPijulParallelMergeTest {

    /**
     * Two sources edit different regions of the same file. The Pijul CRDT
     * applies both as commutative inserts — no conflict markers, no LLM
     * deferral. The merged result contains both edits.
     *
     * Master:  A\nB\nC\nD\nE
     * Source 0: A\nB\nNEW0\nC\nD\nE  (insert after B)
     * Source 1: A\nB\nC\nD\nNEW1\nE  (insert after D)
     *
     * Both inserts are NOVEL (new content, not in master). They touch
     * different positions → they commute in the CRDT.
     */
    @Test
    fun testParallelNonOverlappingMerge() {
        val masterText = "A\nB\nC\nD\nE"
        val masterSpine = LineCas.spine(masterText)
        val masterBaseline = FunnelResidualMerge.buildMasterBaseline(masterSpine)

        val source0Text = "A\nB\nNEW0\nC\nD\nE"
        val source1Text = "A\nB\nC\nD\nNEW1\nE"

        val sources = 2 j { i: Int ->
            LineCas.spine(if (i == 0) source0Text else source1Text)
        }
        val sourceTexts = 2 j { i: Int ->
            if (i == 0) source0Text else source1Text
        }

        val result = FunnelResidualMerge.pijulMerge(sources, masterBaseline, masterText, sourceTexts)

        assertEquals(2, result.sourceCount)
        // Both NEW0 and NEW1 should appear in the merged output
        assertTrue("NEW0" in result.mergedText, "Merged text should contain NEW0: ${result.mergedText}")
        assertTrue("NEW1" in result.mergedText, "Merged text should contain NEW1: ${result.mergedText}")
        // All original master lines preserved
        for (line in listOf("A", "B", "C", "D", "E")) {
            assertTrue(line in result.mergedText, "Merged text should contain $line: ${result.mergedText}")
        }
        // No conflict markers
        assertTrue("<<<<<<<" !in result.mergedText, "No conflict markers in merged text")
        assertTrue("=======" !in result.mergedText, "No conflict markers in merged text")
        assertTrue(">>>>>>>" !in result.mergedText, "No conflict markers in merged text")

        // Both sources contributed patches
        assertEquals(2, result.provenance.size, "Both sources should have provenance entries")
        assertTrue(result.provenance[0].changeCount > 0, "Source 0 should have changes")
        assertTrue(result.provenance[1].changeCount > 0, "Source 1 should have changes")

        println("Merged text:\n${result.mergedText}")
    }

    /**
     * Three sources all add a unique novel line at the same position.
     * The CRDT applies all three inserts — they commute because they're
     * all new content at distinct positions in the source spines.
     */
    @Test
    fun testThreeWayParallelMerge() {
        val masterText = "A\nB\nC"
        val masterSpine = LineCas.spine(masterText)
        val masterBaseline = FunnelResidualMerge.buildMasterBaseline(masterSpine)

        val sourceTexts = listOf(
            "A\nX\nB\nC",
            "A\nB\nY\nC",
            "A\nB\nC\nZ",
        )
        val sources = 3 j { i: Int -> LineCas.spine(sourceTexts[i]) }
        val texts = 3 j { i: Int -> sourceTexts[i] }

        val result = FunnelResidualMerge.pijulMerge(sources, masterBaseline, masterText, texts)

        assertTrue("X" in result.mergedText, "X should be in merged: ${result.mergedText}")
        assertTrue("Y" in result.mergedText, "Y should be in merged: ${result.mergedText}")
        assertTrue("Z" in result.mergedText, "Z should be in merged: ${result.mergedText}")
        assertTrue("<<<<<<<" !in result.mergedText, "No conflict markers")

        println("3-way merged text:\n${result.mergedText}")
    }
}
