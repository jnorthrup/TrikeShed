package borg.trikeshed.collections.associative

import borg.trikeshed.lib.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ordered keyed store: get/remove find what set stored, and telling order survives removes and resizes. */
class LinkedLinearHashMapTest {

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
}
