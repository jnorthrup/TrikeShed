package borg.trikeshed.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunnelHashMapTest {

    @Test fun putAndGet() {
        val m = FunnelHashMap<String, Int>()
        assertNull(m.get("x"))
        m.put("a", 1)
        m.put("b", 2)
        assertEquals(1, m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.get("z"))
    }

    @Test fun putOverwrite() {
        val m = FunnelHashMap<String, Int>()
        assertEquals(null, m.put("k", 10))
        assertEquals(10,   m.put("k", 20))
        assertEquals(20,   m.get("k"))
    }

    @Test fun remove() {
        val m = FunnelHashMap<String, Int>()
        m.put("a", 1); m.put("b", 2)
        assertEquals(1, m.remove("a"))
        assertNull(m.get("a"))
        assertEquals(2, m.get("b"))
        assertNull(m.remove("a"))
    }

    @Test fun resize() {
        val m = FunnelHashMap<Int, Int>(32)
        repeat(2000) { m.put(it, it * 2) }
        repeat(2000) { assertEquals(it * 2, m.get(it), "key=\$it") }
        assertEquals(2000, m.count)
    }
}
