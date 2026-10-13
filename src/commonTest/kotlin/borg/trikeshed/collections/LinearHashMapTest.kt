package borg.trikeshed.collections

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearHashMapTest {

    @Test fun constantPopulationChurnCompactsWithoutGrowing() {
        val m = LinearHashMap<Int, Int?>(4)
        repeat(32) { m[it] = null }
        val capacity = m.totalCapacity
        repeat(2_000) { ordinal ->
            m.remove(ordinal)
            m[ordinal + 32] = null
            assertEquals(32, m.count)
            assertEquals(capacity, m.totalCapacity)
        }
        assertEquals((2000 until 2032).toSet(), m.entries().view.map { it.a }.toSet())
    }

    @Test fun selectedEntriesAreFrozenAcrossMutation() {
        val m = LinearHashMap<Int, Int?>(4)
        repeat(200) { m[it] = if (it % 2 == 0) null else it }
        m.remove(3)
        val selected = m.entries { key, value -> key < 10 && value != null }
        assertEquals(setOf(1 to 1, 5 to 5, 7 to 7, 9 to 9), selected.view.map { it.pair }.toSet())
        m.remove(1)
        m[5] = 500
        assertEquals(setOf(1 to 1, 5 to 5, 7 to 7, 9 to 9), selected.view.map { it.pair }.toSet())
        assertEquals(0, m.entries { _, _ -> false }.size)
        assertEquals(198, m.count)
    }

    @Test fun putAndGet() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<String, Int>()
        assertNull(m.get("x"))
        m.set("a", 1)
        m.set("b", 2)
        assertEquals(1, m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.get("z"))
    }

    @Test fun putOverwrite() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<String, Int>()
        assertEquals(null, m.set("k", 10))
        assertEquals(10,   m.set("k", 20))
        assertEquals(20,   m.get("k"))
    }

    @Test fun remove() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<String, Int>()
        m.set("a", 1); m.set("b", 2)
        assertEquals(1, m.remove("a"))
        assertNull(m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.remove("a"))  // already gone
    }

    @Test fun resize() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<Int, Int>(4)
        repeat(200) { m.set(it, it * 2) }
        repeat(200) { assertEquals(it * 2, m.get(it), "key=$it") }
        assertEquals(200, m.count)
    }

    @Test fun collisionChain() {
        // force collisions: all keys hash to same slot mod 16
        val m = LinearHashMap<String, Int>(16)
        // fabricate collision-prone keys by overriding won't work in common,
        // so just exercise with many puts to trigger probing
        val keys = (0 until 7).map { "key$it" }
        keys.forEachIndexed { i, k -> m.set(k, i) }
        keys.forEachIndexed { i, k -> assertEquals(i, m.get(k)) }
    }
}
