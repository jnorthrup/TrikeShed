package borg.trikeshed.collections.associative

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeMapComplexityTest {
    @Test
    fun orderedUpdatesAndSearchesHaveLogarithmicComparisonBudgets() {
        var comparisons = 0
        val map = TreeMap<Int, Int>(Comparator { a, b ->
            comparisons++
            a.compareTo(b)
        })
        val count = 8192
        for (key in 0 until count) {
            comparisons = 0
            map[key] = key
            assertTrue(comparisons <= 64, "insertion $key used $comparisons comparisons")
        }
        for (key in 0 until count step 31) {
            comparisons = 0
            assertEquals(key, map[key])
            assertTrue(comparisons <= 32, "lookup $key used $comparisons comparisons")
            comparisons = 0
            assertEquals(key, map.rank(key))
            assertTrue(comparisons <= 32, "rank $key used $comparisons comparisons")
            comparisons = 0
            assertEquals(key, map.select(key).a)
            assertEquals(0, comparisons, "full-map select should use subtree counts")
        }
        for (key in 0 until count) {
            comparisons = 0
            assertEquals(key, map.remove(key))
            assertTrue(comparisons <= 64, "deletion $key used $comparisons comparisons")
        }
        assertTrue(map.isEmpty())
    }

    @Test
    fun boundedSnapshotCapturesWithoutScanningAndCachesItsBounds() {
        var comparisons = 0
        val map = TreeMap<Int, Int>(Comparator { a, b ->
            comparisons++
            a.compareTo(b)
        })
        for (key in 0 until 4096) map[key] = key
        val view = map.subMap(1000, 3000)
        comparisons = 0
        val snapshot = view.snapshot()
        assertEquals(0, comparisons)
        map.clear()
        comparisons = 0
        assertEquals(2000, snapshot.size)
        assertTrue(comparisons <= 64, "snapshot bounds must use rank, not scan")
        comparisons = 0
        assertEquals(1000, snapshot[0].a)
        assertEquals(2999, snapshot[1999].a)
        assertEquals(2000, snapshot.size)
        assertEquals(0, comparisons, "cached bounds and indexed reads need no comparisons")
    }
}
