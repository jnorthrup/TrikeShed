package borg.trikeshed.collections.associative

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeMapTest {
    private fun map(): TreeMap<Int, Int?> = TreeMap(naturalOrder<Int>())

    private fun <K, V> entry(key: K, value: V): MutableMap.MutableEntry<K, V> =
        mutableMapOf(key to value).entries.single()

    @Test
    fun randomizedOperationsMatchSortedReferenceList() {
        for (seed in 0 until 4) {
            val random = Random(seed)
            val map = map()
            val reference = mutableListOf<Pair<Int, Int?>>()
            repeat(4000) {
                val key = random.nextInt(-150, 151)
                val index = reference.indexOfFirst { it.first == key }
                val old = reference.getOrNull(index)?.second
                when (random.nextInt(4)) {
                    0, 1 -> {
                        val value = if (random.nextInt(4) == 0) null else random.nextInt()
                        assertEquals(old, map.put(key, value))
                        if (index >= 0) reference[index] = key to value else reference.add(key to value)
                        reference.sortBy { it.first }
                    }
                    2 -> {
                        assertEquals(old, map.remove(key))
                        if (index >= 0) reference.removeAt(index)
                    }
                    else -> {
                        assertEquals(index >= 0, map.containsKey(key))
                        assertEquals(old, map[key])
                    }
                }
                map.checkInvariants()
                assertEquals(reference.size, map.size)
                assertEquals(reference, map.entries.map { it.key to it.value })
                assertEquals(reference.count { it.first < key }, map.rank(key))
                if (reference.isNotEmpty()) {
                    val selected = random.nextInt(reference.size)
                    assertEquals(reference[selected], map.select(selected).pair)
                    assertEquals(reference.first().first, map.firstKey())
                    assertEquals(reference.last().first, map.lastKey())
                }
            }
        }
    }

    @Test
    fun adversarialOrdersMaintainAvlHeightsAndCounts() {
        val size = 1024
        val ascending = (0 until size).toList()
        val alternating = ascending.sortedBy { if (it % 2 == 0) it / 2 else size - it / 2 }
        for (insertion in listOf(ascending, ascending.reversed(), alternating)) {
            for (deletion in listOf(ascending, ascending.reversed(), alternating)) {
                val map = map()
                insertion.forEach {
                    map[it] = it
                    assertTrue(map.checkInvariants() <= 20)
                }
                assertEquals(ascending, map.keys.toList())
                deletion.forEachIndexed { index, key ->
                    assertEquals(key, map.remove(key))
                    assertEquals(size - index - 1, map.size)
                    assertFalse(map.containsKey(key))
                    assertTrue(map.checkInvariants() <= 20)
                }
                assertTrue(map.isEmpty())
                assertEquals(0, map.checkInvariants())
            }
        }
    }

    @Test
    fun rangeViewsStayLiveAndMutateOnlyTheirBounds() {
        val map = map()
        (0..9).forEach { map[it] = it }
        val middle = map.subMap(2, 8)
        val nested = middle.tailMap(4).headMap(7)
        val keys = nested.keys
        val values = nested.values
        val entries = nested.entries
        assertEquals(listOf(4, 5, 6), keys.toList())
        map.remove(5)
        assertEquals(listOf(4, 6), keys.toList())
        nested[5] = null
        assertTrue(map.containsKey(5))
        assertEquals(listOf(4, null, 6), values.toList())
        assertEquals(3, entries.size)
        assertEquals(4, nested.firstKey())
        assertEquals(6, nested.lastKey())
        assertEquals(0, nested.rank(-100))
        assertEquals(1, nested.rank(5))
        assertEquals(3, nested.rank(100))
        assertEquals(5 to null, nested.select(1).pair)
        assertNull(nested[3])
        assertNull(nested.remove(3))
        assertFalse(nested.containsKey(7))
        assertFailsWith<IllegalArgumentException> { nested[7] = 7 }
        assertFailsWith<IllegalArgumentException> { nested[3] = 3 }
        nested.clear()
        assertEquals(listOf(0, 1, 2, 3, 7, 8, 9), map.keys.toList())
        assertTrue(keys.isEmpty())
        map[6] = 60
        assertEquals(listOf(6), keys.toList())
        assertEquals(listOf(2, 3, 6, 7), middle.keys.toList())
        map.checkInvariants()
    }

    @Test
    fun nestedBoundsRespectExcludedUpperEndpoint() {
        val view = map().subMap(2, 8)
        assertTrue(view.headMap(2).isEmpty())
        assertTrue(view.headMap(8).isEmpty())
        assertTrue(view.subMap(2, 2).isEmpty())
        assertTrue(view.subMap(2, 8).isEmpty())
        assertFailsWith<IllegalArgumentException> { view.tailMap(8) }
        assertFailsWith<IllegalArgumentException> { view.subMap(8, 8) }
        assertFailsWith<IllegalArgumentException> { view.subMap(1, 5) }
        assertFailsWith<IllegalArgumentException> { view.subMap(5, 9) }
        assertFailsWith<IllegalArgumentException> { view.subMap(6, 5) }
        assertFailsWith<IllegalArgumentException> { view.headMap(1) }
        assertFailsWith<IllegalArgumentException> { view.headMap(9) }
        assertFailsWith<IllegalArgumentException> { view.tailMap(1) }
        assertFailsWith<IllegalArgumentException> { view.tailMap(9) }
        assertFailsWith<IllegalArgumentException> { view.headMap(2).tailMap(2) }
        assertFailsWith<NoSuchElementException> { view.firstKey() }
        assertFailsWith<NoSuchElementException> { view.lastKey() }
        assertFailsWith<IndexOutOfBoundsException> { view.select(0) }
    }

    @Test
    fun comparatorEqualityKeepsOriginalKeysIncludingCollectionMutations() {
        data class Key(val order: Int, val name: String)
        val comparator = compareBy<Key> { it.order }
        val map = TreeMap<Key, Int?>(comparator)
        val original = Key(1, "original")
        val equivalent = Key(1, "equivalent")
        assertNull(map.put(original, null))
        assertTrue(map.containsKey(equivalent))
        assertNull(map.put(equivalent, 2))
        assertEquals(1, map.size)
        assertSame(original, map.firstKey())
        assertTrue(map.entries.contains(entry(equivalent, 2)))
        assertTrue(map.keys.contains(equivalent))
        assertFalse(map.entries.remove(entry(equivalent, null)))
        assertTrue(map.keys.retainAll(listOf(Key(2, "other"))))
        map[original] = null
        assertTrue(map.entries.remove(entry(equivalent, null)))
        map[original] = null
        assertTrue(map.keys.remove(equivalent))
        map[original] = 3
        assertFalse(map.keys.retainAll(listOf(equivalent)))
        assertFalse(map.entries.retainAll(listOf(entry(equivalent, 3))))
        assertTrue(map.entries.removeAll(listOf(entry(equivalent, 3))))
        map[original] = null
        assertTrue(map.keys.removeAll(listOf(equivalent)))
        assertTrue(map.isEmpty())
        map[original] = 4
        assertEquals(4, map.subMap(equivalent, Key(2, "end"))[original])
        map.checkInvariants()
    }

    @Test
    fun entriesKeysAndValuesAreOrderedBackedMutableViews() {
        val map = map()
        val entries = map.entries
        val keys = map.keys
        val values = map.values
        map.putAll(mapOf(3 to null, 1 to 10, 2 to 10, 4 to 40))
        assertEquals(listOf(1, 2, 3, 4), keys.toList())
        assertEquals(listOf(10, 10, null, 40), values.toList())
        assertTrue(values.remove(10))
        assertFalse(map.containsKey(1))
        assertTrue(map.containsKey(2))
        assertTrue(values.remove(null))
        assertFalse(map.containsKey(3))
        val iterator = entries.iterator()
        assertFailsWith<IllegalStateException> { iterator.remove() }
        val first = iterator.next()
        assertEquals(2, first.key)
        assertEquals(10, first.setValue(null))
        assertTrue(map.containsKey(2))
        assertNull(map[2])
        map[2] = 22
        assertEquals(22, first.value)
        iterator.remove()
        assertFailsWith<IllegalStateException> { iterator.remove() }
        assertFailsWith<IllegalStateException> { first.setValue(23) }
        assertEquals(22, first.value)
        assertEquals(4, iterator.next().key)
        iterator.remove()
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
        assertTrue(map.isEmpty())
        map[8] = null
        val keyIterator = keys.iterator()
        assertEquals(8, keyIterator.next())
        keyIterator.remove()
        map[9] = 90
        val valueIterator = values.iterator()
        assertEquals(90, valueIterator.next())
        valueIterator.remove()
        assertTrue(map.isEmpty())
        assertFailsWith<UnsupportedOperationException> { keys.add(1) }
        assertFailsWith<UnsupportedOperationException> { values.add(1) }
        assertFailsWith<UnsupportedOperationException> { entries.add(entry(1, 1)) }
    }

    @Test
    fun iteratorSurvivesOwnRemovalsAndValueChangesButRejectsStructuralChanges() {
        val map = map()
        (0 until 100).forEach { map[it] = it }
        val iterator = map.subMap(20, 80).entries.iterator()
        val visited = mutableListOf<Int>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            visited.add(entry.key)
            if (entry.key % 2 == 0) iterator.remove() else entry.setValue(-entry.key)
        }
        assertEquals((20 until 80).toList(), visited)
        map.checkInvariants()
        val updates = map.entries.iterator()
        map[0] = 123
        assertEquals(123, updates.next().value)
        val failed = map.subMap(20, 80).keys.iterator()
        map[200] = 200
        assertFailsWith<ConcurrentModificationException> { failed.hasNext() }
        assertFailsWith<ConcurrentModificationException> { failed.next() }
        assertFailsWith<ConcurrentModificationException> { failed.remove() }
        val cleared = map.entries.iterator()
        map.clear()
        assertFailsWith<ConcurrentModificationException> { cleared.next() }
    }

    @Test
    fun snapshotsRetainMappingsAcrossAllMutationPaths() {
        val map = map()
        (0..9).forEach { map[it] = it }
        val full = map.snapshot()
        val bounded = map.subMap(2, 5).snapshot()
        // First access is deliberately after every original node has been removed.
        map.entries.first().setValue(null)
        map.values.remove(2)
        map[3] = 300
        map.subMap(4, 8).clear()
        map.clear()
        map[99] = null
        assertEquals(10, full.size)
        (0..9).forEach { assertEquals(it to it, full[it].pair) }
        assertEquals(3, bounded.size)
        (0..2).forEach { assertEquals((it + 2) to (it + 2), bounded[it].pair) }
        assertFailsWith<IndexOutOfBoundsException> { full[-1] }
        assertFailsWith<IndexOutOfBoundsException> { full[10] }
        assertFailsWith<IndexOutOfBoundsException> { bounded[3] }
        assertEquals(99 to null, map.select(0).pair)
        assertFailsWith<IndexOutOfBoundsException> { map.select(-1) }
        assertFailsWith<IndexOutOfBoundsException> { map.select(1) }
        val empty = map.headMap(0).snapshot()
        assertEquals(0, empty.size)
        assertFailsWith<IndexOutOfBoundsException> { empty[0] }
    }

    @Test
    fun snapshotsDoNotTraverseAndQueriesUseLogarithmicComparisons() {
        var comparisons = 0
        val map = TreeMap<Int, Int>(Comparator { a, b ->
            comparisons++
            a.compareTo(b)
        })
        (0 until 4096).forEach { map[it] = it }
        val bounded = map.subMap(1000, 3000)
        comparisons = 0
        val snapshot = bounded.snapshot()
        assertEquals(0, comparisons)
        map[2000] = -1
        comparisons = 0
        assertEquals(2000, snapshot.size)
        assertEquals(2000 to 2000, snapshot[1000].pair)
        assertTrue(comparisons <= 40, "Snapshot bounds: $comparisons comparisons")
        comparisons = 0
        assertEquals(1000, bounded.rank(2000))
        assertTrue(comparisons <= 50, "Rank: $comparisons comparisons")
        comparisons = 0
        assertEquals(2000 to -1, bounded.select(1000).pair)
        assertTrue(comparisons <= 40, "Select bounds: $comparisons comparisons")
        comparisons = 0
        assertEquals(2000, bounded.size)
        assertTrue(comparisons <= 40, "View size: $comparisons comparisons")
        comparisons = 0
        assertEquals(-1, map[2000])
        assertTrue(comparisons <= 20, "Lookup: $comparisons comparisons")
        comparisons = 0
        map[4096] = 4096
        assertTrue(comparisons <= 40, "Insertion: $comparisons comparisons")
        comparisons = 0
        assertEquals(-1, map.remove(2000))
        assertTrue(comparisons <= 40, "Deletion: $comparisons comparisons")
        comparisons = 0
        assertEquals(map.size, map.entries.count { it.key == it.value })
        assertEquals(0, comparisons, "Unmodified full traversal does not re-search entries")
        map.checkInvariants()
    }

    @Test
    fun reverseOrderAndNullableKeysUseTheSuppliedComparator() {
        val map = TreeMap<Int?, String?>(nullsFirst(reverseOrder<Int>()))
        map[2] = "two"
        map[1] = null
        map[null] = "null"
        map[3] = "three"
        assertEquals(listOf(null, 3, 2, 1), map.keys.toList())
        assertNull(map.firstKey())
        assertEquals(1, map.lastKey())
        assertEquals(listOf(null, 3), map.headMap(2).keys.toList())
        assertEquals(listOf(3, 2), map.subMap(3, 1).keys.toList())
        assertEquals(listOf(null, 3, 2, 1), map.tailMap(null).keys.toList())
        assertEquals(0, map.rank(null))
        assertEquals(2, map.rank(2))
        assertFailsWith<IllegalArgumentException> { map.subMap(1, 3) }
        assertTrue(map.keys.remove(null))
        assertFalse(map.containsKey(null))
        map.checkInvariants()
    }

    @Test
    fun bulkCollectionOperationsAndMapEquality() {
        val map = mapOf(3 to 30, 1 to null, 2 to 20).toCommonSortedMap()
        assertEquals(listOf(1, 2, 3), map.keys.toList())
        assertEquals(mapOf(1 to null, 2 to 20, 3 to 30), map)
        assertEquals(mapOf(1 to null, 2 to 20, 3 to 30).hashCode(), map.hashCode())
        assertEquals(mapOf<Int, Int?>(1 to null).entries.single(), map.entries.first())
        assertEquals(mapOf(1 to null).entries.single().hashCode(), map.entries.first().hashCode())
        assertTrue(map.values.retainAll(listOf(null, 30)))
        assertEquals(listOf(1, 3), map.keys.toList())
        assertTrue(map.values.removeAll(listOf(null)))
        assertEquals(listOf(3), map.keys.toList())
        assertTrue(map.entries.removeAll(map.entries))
        assertTrue(map.isEmpty())
        map.putAll(mapOf(1 to 1, 2 to 2))
        assertFalse(map.keys.retainAll(map.keys))
        assertTrue(map.keys.removeAll(map.keys))
        map.putAll(mapOf(1 to 1, 2 to 2))
        map.values.clear()
        assertTrue(map.isEmpty())
        map[1] = 1
        map.entries.clear()
        assertTrue(map.isEmpty())
        map[1] = 1
        map.keys.clear()
        assertTrue(map.isEmpty())
        val descending = mapOf(1 to "a", 3 to "c", 2 to "b").toCommonSortedMap(reverseOrder())
        assertEquals(listOf(3, 2, 1), descending.keys.toList())
        data class Key(val order: Int, val label: String)
        val a = Key(1, "a")
        val b = Key(1, "b")
        val collapsed = linkedMapOf(a to 1, b to 2).toCommonSortedMap(compareBy { it.order })
        assertEquals(1, collapsed.size)
        assertSame(a, collapsed.firstKey())
        assertEquals(2, collapsed[a])
    }
}
