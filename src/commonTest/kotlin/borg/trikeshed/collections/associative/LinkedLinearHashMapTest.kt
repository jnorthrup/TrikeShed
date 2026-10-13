package borg.trikeshed.collections.associative

import borg.trikeshed.lib.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ordered keyed store: get/remove find what set stored, and telling order survives removes and resizes. */
class LinkedLinearHashMapTest {
    private data class CollisionKey(val id: Int) { override fun hashCode(): Int = 1 }

    @Test
    fun collisionsAndNullableValuesRetainOrderThroughGrowthAndCompaction() {
        val m = LinkedLinearHashMap<CollisionKey, Int?>(4)
        val keys = List(128) { CollisionKey(it) }
        keys.forEach { m[it] = if (it.id % 2 == 0) null else it.id }
        val snapshot = m.entriesInOrder()
        val evens = keys.filter { it.id % 2 == 0 }
        evens.forEach { m.remove(it) }
        evens.asReversed().forEach { m[it] = null }
        val expected = keys.filter { it.id % 2 != 0 } + evens.asReversed()
        assertEquals(expected, m.entriesInOrder().view.map { it.a })
        assertEquals(evens.asReversed(), m.entriesInOrder { _, value -> value == null }.view.map { it.a })
        assertEquals(keys, snapshot.view.map { it.a }, "returned entries remain frozen across slot rehash")
        keys.filter { it.id % 2 != 0 }.forEach { assertEquals(it.id, m[it]) }
        assertEquals(128, m.count)
    }

    @Test
    fun constantPopulationChurnCompactsWithoutGrowingAndPreservesOrder() {
        val m = LinkedLinearHashMap<Int, Int?>(4)
        val expected = ArrayDeque<Int>()
        repeat(32) { key -> m[key] = null; expected.addLast(key) }
        val capacity = m.totalCapacity
        repeat(2_000) { ordinal ->
            val gone = expected.removeFirst()
            m.remove(gone)
            val key = ordinal + 32
            m[key] = if (key % 2 == 0) null else key
            expected.addLast(key)
            assertEquals(32, m.count)
            assertEquals(capacity, m.totalCapacity, "dead slots must not grow a constant population")
        }
        assertEquals(expected.toList(), m.entriesInOrder().view.map { it.a })
        val visited = mutableListOf<Int>()
        m.forEachInOrder { key, _ -> visited.add(key) }
        assertEquals(expected.toList(), visited)
        assertTrue(m.entriesInOrder().view.any { it.b == null }, "nullable values remain live through compaction")
    }

    @Test
    fun replacementAtLoadThresholdKeepsCapacityAndOrder() {
        val m = LinkedLinearHashMap<Int, Int?>(8)
        repeat(4) { m[it] = it }
        val capacity = m.totalCapacity
        repeat(100) { m[0] = null; assertEquals(3, m[3]); assertNull(m.remove(1000)) }
        assertEquals(capacity, m.totalCapacity)
        assertEquals(listOf(0, 1, 2, 3), m.entriesInOrder().view.map { it.a })
        assertEquals(4, m.count)
    }

    private fun LinkedLinearHashMap<String, Int>.keysInOrder(): List<String> =
        entriesInOrder().view.map { it.a }

    @Test
    fun setGetRemoveAgreeOnTheUserKey() {
        val m = LinkedLinearHashMap<String, Int>(4)
        assertNull(m.set("a", 1))
        assertEquals(1, m["a"])
        assertEquals(1, m.set("a", 2), "re-set returns the old value")
        assertEquals(2, m["a"])
        assertEquals(1, m.count)
        assertEquals(2, m.remove("a"))
        assertNull(m["a"])
        assertNull(m.remove("a"))
        assertEquals(0, m.count)
    }

    @Test
    fun tellingOrderSurvivesRemovesResetsAndResize() {
        val m = LinkedLinearHashMap<String, Int>(4)
        val keys = (0 until 500).map { "k$it" }
        keys.forEachIndexed { i, k -> m[k] = i }
        assertEquals(keys, m.keysInOrder(), "order across many resizes")

        m.remove("k1"); m.remove("k250"); m.remove("k499")
        m["k1"] = -1
        m["k3"] = 33
        val expect = keys.filter { it != "k1" && it != "k250" && it != "k499" } + "k1"
        assertEquals(expect, m.keysInOrder(), "removed key re-set goes last; a present key re-set keeps its place")
        assertEquals(33, m["k3"])
        assertEquals(-1, m["k1"])
        assertEquals(expect.size, m.count)
    }

    @Test
    fun selectedEntriesRetainOrderAndSnapshotAcrossMutation() {
        val m = LinkedLinearHashMap<String, Int>(4)
        repeat(500) { m["k$it"] = it }
        m.remove("k2")
        m["k2"] = 502
        m["k4"] = 504
        val selected = m.entriesInOrder { _, value -> value % 2 == 0 }
        val expected = m.entriesInOrder().view.filter { it.b % 2 == 0 }.map { it.a to it.b }
        assertEquals(expected, selected.view.map { it.a to it.b })
        m.remove("k4")
        m["k500"] = 500
        assertEquals(expected, selected.view.map { it.a to it.b }, "selection owns its frozen rows")
        assertEquals(emptyList(), m.entriesInOrder { _, _ -> false }.view.toList())
    }
}
