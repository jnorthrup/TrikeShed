package borg.trikeshed.splat

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CasSpineMotionTest {
    private fun text(cas: CasStore, spine: LineSpine): List<String> =
        spine.view.map { node -> cas.get(node.contentCid)!!.decodeToString() }

    @Test
    fun lineMotionExtrapolatesAndRebuildsNeighborStampedSpine() {
        val cas = CasStore.inMemory()
        val previous = LineCas.ingestLines(cas, listOf("a", "b", "c", "d"))
        val current = LineCas.ingestLines(cas, listOf("b", "a", "d", "c"))

        val iteration = CasSpineMotion.iterate(cas, previous, current)

        assertEquals(4, iteration.motion.size)
        assertEquals(listOf("b", "d", "a", "c"), text(cas, iteration.next))
        assertNotEquals(iteration.currentCid, iteration.nextCid)
        assertEquals(iteration.motion.size + 1, iteration.eigenSignature.a[0])
        assertTrue(iteration.motion.view.sumOf { it.b } in 0.999999999..1.000000001)

        // Rebuilt nodes carry fresh ordinal and neighbor stamps, not stale nodes from `current`.
        iteration.next.view.forEachIndexed { index, node -> assertEquals(index, node.ordinal) }
        assertEquals(LineCas.spineCid(iteration.next), iteration.nextCid)
    }

    @Test
    fun casLineFeaturesAreDenseAndDimensionSelectable() {
        val cas = CasStore.inMemory()
        val spine = LineCas.ingestLines(cas, listOf("alpha", "beta", "gamma"))
        val features = CasSpineMotion.features(spine[1], spine.size, dimensions = 12)
        assertEquals(12, features.size)
        assertEquals(0.5, features[0])
        assertTrue(features.view.all { it in 0.0..1.0 })
    }
}
