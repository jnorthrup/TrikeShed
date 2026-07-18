package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class ColKTest {

    @Test
    fun testRowVecAsFaceted() {
        // Create a simple RowVec
        val cells = seriesOfAny(listOf("foo", 42, true))
        val keys = seriesOf(listOf("name", "age", "active"))
        val rowVec = cellsToRowVec(cells, keys)

        // Lift to FacetedRow
        val faceted = rowVec.asFaceted()

        // Test Width
        assertEquals(3, faceted[ColK.Width])

        // Test ByIndex
        assertEquals("foo", faceted[ColK.ByIndex(0)])
        assertEquals(42, faceted[ColK.ByIndex(1)])
        assertEquals(true, faceted[ColK.ByIndex(2)])

        // Test ByName
        assertEquals("foo", faceted[ColK.ByName("name")])
        assertEquals(42, faceted[ColK.ByName("age")])
        assertEquals(true, faceted[ColK.ByName("active")])

        // Test Meta
        val metaSeries = faceted[ColK.Meta]
        assertEquals(3, metaSeries.size)
        assertEquals("name", metaSeries[0].name)
        assertEquals("age", metaSeries[1].name)
        assertEquals("active", metaSeries[2].name)
    }

    @Test
    fun testByNameNotFoundThrows() {
        val cells = seriesOfAny(listOf("foo"))
        val keys = seriesOf(listOf("name"))
        val rowVec = cellsToRowVec(cells, keys)
        val faceted = rowVec.asFaceted()

        assertFailsWith<NoSuchElementException> {
            faceted[ColK.ByName("missing")]
        }
    }

    @Test
    fun testFacetedAsRowVec() {
        val cells = seriesOfAny(listOf("bar", 99, false))
        val keys = seriesOf(listOf("first", "second", "third"))
        val originalRowVec = cellsToRowVec(cells, keys)

        val faceted = originalRowVec.asFaceted()
        val restoredRowVec = faceted.asRowVec()

        assertEquals(originalRowVec.size, restoredRowVec.size)

        for (i in 0 until originalRowVec.size) {
            val origPair = originalRowVec[i]
            val restPair = restoredRowVec[i]

            assertEquals(origPair.a, restPair.a)
            assertEquals(origPair.b().name, restPair.b().name)
        }
    }
}
