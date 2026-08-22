package borg.trikeshed.lib.cascade

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ports the assertions of the retired collections.associative.trie.{Trie,RadixTree} onto cascade.Trie. */
class TrieTest {
    private val d = Int.MIN_VALUE
    private fun k(vararg s: String) = s.toList().toSeries()

    @Test
    fun segmentTrie() {
        val trie = Trie<String, Int>(monoid(0) { _, b -> b })
        assertFalse(k() in trie)
        trie.put(k("a", "b", "c"), d)
        assertFalse(k() in trie)
        assertFalse(k("a") in trie)
        assertFalse(k("a", "b") in trie)
        assertTrue(k("a", "b", "c") in trie)
        assertEquals(d, trie.leaf(k("a", "b", "c")))
        trie.put(k("a", "b"), d)
        assertTrue(k("a", "b") in trie)
        trie.put(k("a", "b", "d", "e"), d)
        assertTrue(k("a", "b", "d", "e") in trie)
        assertEquals(3, trie.size)
    }

    @Test
    fun putReplacesLeafOnly() {
        val trie = Trie<String, Int>(monoid(0) { _, b -> b })
        trie.put(k("a", "b", "c"), 1)
        trie.put(k("a", "b"), 2)
        assertEquals(2, trie.leaf(k("a", "b")))
        assertEquals(1, trie.leaf(k("a", "b", "c")))
        assertEquals(null, trie.leaf(k("no", "such")))
        trie.put(k("k"), 1); trie.put(k("k"), 2)
        assertEquals(2, trie.leaf(k("k")))
    }

    @Test
    fun keysAndPrefixLikeRadixTree() {
        val t = Trie<Char, Int>(Count)
        for (w in listOf("foo", "bar", "baz", "ab", "abc")) t.put(w.toSeries(), 1)
        val all = t.keys().map { it.toList().joinToString("") }
        assertEquals(listOf("ab", "abc", "bar", "baz", "foo"), all)
        assertEquals(listOf("bar", "baz"), t.keys("ba".toSeries()).map { it.toList().joinToString("") })
        assertEquals(2, t["ba".toSeries()])
        assertEquals(5, t[t.a])
    }

    @Test
    fun emptyKeyIsTheRoot() {
        val t = Trie<Char, Int>(Count)
        t.put("".toSeries(), 1)
        assertTrue("".toSeries() in t)
        assertEquals(1, t[t.a])
        assertEquals(listOf(""), t.keys().map { it.toList().joinToString("") })
    }

    @Test
    fun removePrunesAndRereduces() {
        val t = Trie<Char, Int>(Count)
        t.put("abc".toSeries(), 1); t.put("abd".toSeries(), 1)
        assertEquals(2, t["ab".toSeries()])
        assertEquals(1, t.remove("abc".toSeries()))
        assertEquals(null, t.remove("abc".toSeries()))
        assertEquals(1, t["ab".toSeries()])
        assertTrue(t.unseen("abc".toSeries()))
        assertFalse(t.unseen("abd".toSeries()))
    }
}
