package borg.trikeshed.collections.multiindex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1 RED — Retract removes position from exact/range/prefix views WITHOUT
 * renumbering unrelated records.
 *
 * The plan gate: "retract removes a position from exact/range/prefix views
 * without renumbering unrelated records"
 */
class RetractWithoutRenumberingTest {

    @Test
    fun retractDoesNotRenumberSurroundingPositions() {
        val container = MultiIndexContainer<String>()
        val byHash = MultiIndexK.ByHash { it }

        container.registerHash(byHash)

        val pos0 = container.add("aaa")
        val pos1 = container.add("bbb")
        val pos2 = container.add("ccc")
        val pos3 = container.add("ddd")
        val pos4 = container.add("eee")

        // Retract pos2 (ccc)
        container.retract(pos2)

        // Unrelated records must keep their original positions
        assertEquals("aaa", container[pos0], "pos0 must not be renumbered")
        assertEquals("bbb", container[pos1], "pos1 must not be renumbered")
        assertEquals("ddd", container[pos3], "pos3 must not be renumbered")
        assertEquals("eee", container[pos4], "pos4 must not be renumbered")

        // Retracted position must not be in the hash view
        val hashResults = (0 until container.size).map { container[it] }.filterNotNull()
        assertTrue(!hashResults.contains("ccc"), "retracted value must not be in hash view")
    }

    @Test
    fun retractFromRangeViewDoesNotShiftRemaining() {
        val container = MultiIndexContainer<Int>()
        val byOrder = MultiIndexK.ByOrder { it }

        container.registerOrder(byOrder)

        val pos0 = container.add(10)
        val pos1 = container.add(20)
        val pos2 = container.add(30)
        val pos3 = container.add(40)
        val pos4 = container.add(50)

        container.retract(pos2)

        // Range view must skip the retracted position but not shift indices
        val ordered = container.facet(byOrder)
        val values = (0 until ordered.size).map { ordered[it] }.filterNotNull()

        assertEquals(listOf(10, 20, 40, 50), values, "range view must skip retracted, no shift")
    }
}
