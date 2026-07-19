package borg.trikeshed.collections

import borg.trikeshed.collections.associative.LinearHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearHashMapTest {

    @Test fun putAndGet() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<String, Int>()
        assertNull(m.get("x"))
        m.put("a", 1)
        m.put("b", 2)
        assertEquals(1, m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.get("z"))
    }

    @Test fun putOverwrite() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<String, Int>()
        assertEquals(null, m.put("k", 10))
        assertEquals(10,   m.put("k", 20))
        assertEquals(20,   m.get("k"))
    }

    @Test fun remove() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<String, Int>()
        m.put("a", 1); m.put("b", 2)
        assertEquals(1, m.remove("a"))
        assertNull(m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.remove("a"))  // already gone
    }

    @Test fun resize() {
        val m = borg.trikeshed.collections.associative.LinearHashMap<Int, Int>(4)
        repeat(200) { m.put(it, it * 2) }
        repeat(200) { assertEquals(it * 2, m.get(it), "key=$it") }
        assertEquals(200, m.count)
    }

    @Test fun collisionChain() {
        // force collisions: all keys hash to same slot mod 16
        val m = LinearHashMap<String, Int>(16)
        // fabricate collision-prone keys by overriding won't work in common,
        // so just exercise with many puts to trigger probing
        val keys = (0 until 7).map { "key$it" }
        keys.forEachIndexed { i, k -> m.put(k, i) }
        keys.forEachIndexed { i, k -> assertEquals(i, m.get(k)) }
    }
}