package borg.trikeshed.common

import borg.trikeshed.collections.associative.trie.Trie
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals


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
        // empty lookup should still be false after entries exist
        assertFalse(trie.contains(*emptyArray()))
        assertFalse(trie.contains("a"))
        assertFalse(trie.contains("a", "b"))
        assertTrue(trie.contains("a", "b", "c"))
        assertEquals(d, trie["a", "b", "c"])

        trie.add(d, "a", "b")
        assertTrue(trie.contains("a", "b"))
        assertEquals(d, trie["a", "b"])

        trie.add(d, "a", "b", "d", "e")
        assertTrue(trie.contains("a", "b", "d", "e"))
        assertEquals(d, trie["a", "b", "d", "e"])
    }
}
