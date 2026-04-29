package borg.trikeshed.collections.associative.trie

import borg.trikeshed.lib.toArray
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertTrue

class RadixTreeTest {

    @Test
    fun keysContainInserted() {
        val tree = RadixTree<Char>()
        tree + "foo".toSeries()
        tree + "bar".toSeries()
        tree + "baz".toSeries()
        val keys = tree.keys()
        val strings = keys.map { String(it.toArray()) }
        assertTrue(strings.contains("foo"))
        assertTrue(strings.contains("bar"))
        assertTrue(strings.contains("baz"))
    }

    @Test
    fun splitAndTermPreserved() {
        val tree = RadixTree<Char>()
        tree + "ab".toSeries()
        tree + "abc".toSeries()
        val keys = tree.keys().map { String(it.toArray()) }.toSet()
        assertTrue(keys.contains("ab"))
        assertTrue(keys.contains("abc"))
    }

    @Test
    fun emptySeriesIgnored() {
        val tree = RadixTree<Char>()
        tree + "".toSeries()
        assertTrue(tree.keys().isEmpty())
    }
}
