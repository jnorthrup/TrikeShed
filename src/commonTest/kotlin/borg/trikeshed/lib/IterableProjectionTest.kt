package borg.trikeshed.lib

import kotlin.test.*

class IterableProjectionTest {
    @Test
    fun listProjectionDefersTransformAndRetainsIndexedBacking() {
        val values = mutableListOf(2, 3)
        val source: Iterable<Int> = values
        var calls = 0
        val projected = source α { calls++; it * 5 }
        assertEquals(0, calls)
        values[1] = 7
        assertEquals(35, projected[1])
        assertEquals(1, calls)
        assertEquals(2, projected.size)
    }

    @Test
    fun iterableSeriesRetainsItsOracleWithoutEnumerating() {
        var reads = 0
        val source: Iterable<Int> = (3 j { index: Int -> reads++; index }).view
        val projected = source α { it + 1 }
        assertEquals(0, reads)
        assertEquals(3, projected[2])
        assertEquals(1, reads)
    }

    @Test
    fun unindexedIterableEnumeratesOnceButDefersTransform() {
        var traversals = 0
        var transforms = 0
        val source = Iterable { traversals++; listOf(1, 2).iterator() }
        val projected = source α { transforms++; it * 2 }
        assertEquals(1, traversals)
        assertEquals(0, transforms)
        assertEquals(4, projected[1])
        assertEquals(1, transforms)
        assertEquals(1, traversals)
    }
}
