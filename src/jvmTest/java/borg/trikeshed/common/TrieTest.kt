package borg.trikeshed.common

import borg.trikeshed.common.collections.associative.trie.Trie
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test


/**
 * Created by kenny on 6/6/16.
 */
class TrieTest {
    val d: Int = Int.MIN_VALUE

    @Test
    fun stringTrie() {
        val trie = Trie()
        assertFalse(trie.contains(*emptyArray()))

        trie.add(d, "a", "b", "c")
        assertFalse(trie.contains("a"))
        assertFalse(trie.contains("a", "b"))
        assertTrue(trie.contains("a", "b", "c"))

        trie.add(d, "a", "b")
        assertTrue(trie.contains("a", "b"))

        trie.add(d, "a", "b", "d", "e")
        assertTrue(trie.contains("a", "b", "d", "e"))
    }
}
