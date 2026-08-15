package borg.trikeshed.cas

/*
 * MEASURED (post receipt-split: strict-INHERITED vs INHERITED_CROSS)
 * sources.size = 57
 * receipt.novelCount = 7
 * receipt.relocatedCount = 9
 * receipt.inheritedCount = 0   // theorem: provably 0 via merge()
 * receipt.inheritedCrossCount = 1
 * receipt.kept.size = 16
 * receipt.dropped.size = 1
 * residualAtoms = 77
 * totalSourceAtoms = 2294
 */

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import borg.trikeshed.lib.get

class FunnelResidualMerge57WayEvidenceTest {

    @Test
    fun test57WayMerge() {
        val masterLines = (0 until 40).map { "L${it.toString().padStart(2, '0')}" }
        val masterText = masterLines.joinToString("\n")
        val masterSpine = LineCas.spine(masterText)
        val masterBaseline = FunnelResidualMerge.buildMasterBaseline(masterSpine)

        val sources = mutableListOf<LineSpine>()

        // S0..S6 (7 sources): share ONE identical novel line
        val novelSharedLine = "NOVEL_SHARED"
        for (i in 0 until 7) {
            val lines = masterLines.toMutableList()
            lines.add(10, novelSharedLine)
            sources.add(LineCas.spine(lines.joinToString("\n")))
        }

        // S7..S13 (7 sources): each adds a UNIQUE novel line
        for (i in 0 until 7) {
            val lines = masterLines.toMutableList()
            lines.add(20, "NOVEL_$i")
            sources.add(LineCas.spine(lines.joinToString("\n")))
        }

        // S14..S20 (7 sources): relocate one master line
        for (i in 0 until 7) {
            val lines = masterLines.toMutableList()
            val relocated = lines.removeAt(30)
            lines.add(5, relocated)
            sources.add(LineCas.spine(lines.joinToString("\n")))
        }

        // S21..S56 (36 sources): identical to M
        for (i in 0 until 36) {
            sources.add(LineCas.spine(masterText))
        }

        assertEquals(57, sources.size)

        val sourcesSeries = sources.size j { i: Int -> sources[i] }
        val receipt = FunnelResidualMerge.merge(sourcesSeries, masterBaseline)

        val totalSourceAtoms = sources.sumOf { it.size }
        val residualAtoms = sources.indices.sumOf { s ->
            FunnelResidualMerge.residualsOf(sources[s], masterBaseline, FunnelResidualMerge.SourceIdx(s)).size
        }

        println("sources.size = ${sources.size}")
        println("receipt.novelCount = ${receipt.novelCount}")
        println("receipt.relocatedCount = ${receipt.relocatedCount}")
        println("receipt.inheritedCount = ${receipt.inheritedCount}")
        println("receipt.inheritedCrossCount = ${receipt.inheritedCrossCount}")
        println("receipt.kept.size = ${receipt.kept.size}")
        println("receipt.dropped.size = ${receipt.dropped.size}")
        println("residualAtoms = $residualAtoms")
        println("totalSourceAtoms = $totalSourceAtoms")

        assertEquals(7, receipt.novelCount)
        // Theorem (see FunnelResidualMerge.ClusterGrade): via merge(), strict-INHERITED
        // is provably unreachable — residualsOf emits only funnel misses and
        // gradeClusters re-queries the same frozen index.
        assertEquals(0, receipt.inheritedCount, "merge() must never produce strict-INHERITED drops")
        // Red test: Expected relocated behavior according to the 57-way test spec.
        assertTrue(receipt.relocatedCount >= 1, "receipt.relocatedCount should be >= 1 but was ${receipt.relocatedCount}")
        assertTrue(receipt.inheritedCount >= 1 || receipt.dropped.size > 0)
        assertEquals(receipt.novelCount + receipt.relocatedCount, receipt.kept.size)

        // Assert every kept NOVEL cluster comes from a single SourceIdx
        for (i in 0 until receipt.kept.size) {
            val gc = receipt.kept[i]
            if (gc.grade == FunnelResidualMerge.ClusterGrade.NOVEL) {
                val copyCount = gc.cluster.copies.size
                assertTrue(copyCount == 1, "Expected NOVEL to have 1 copy, found $copyCount")
            }
        }

        // Check NOVEL_SHARED logic (it should be INHERITED_CROSS)
        var sharedFound = false
        for (i in 0 until receipt.dropped.size) {
            val gc = receipt.dropped[i]
            if (gc.grade == FunnelResidualMerge.ClusterGrade.INHERITED_CROSS && gc.cluster.copies.size == 7) {
                sharedFound = true
            }
        }
        for (i in 0 until receipt.kept.size) {
            val gc = receipt.kept[i]
            if (gc.grade == FunnelResidualMerge.ClusterGrade.INHERITED_CROSS && gc.cluster.copies.size == 7) {
                sharedFound = true
            }
        }
        assertTrue(sharedFound, "Expected one cluster with copy-count 7 for NOVEL_SHARED")

        assertTrue(residualAtoms < totalSourceAtoms)
    }
}
