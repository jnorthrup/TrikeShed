package borg.trikeshed.common

import borg.trikeshed.collections.associative.trie.Trie
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrieJvmTest {
    val d: Int = Int.MIN_VALUE

    @Test
    fun stringTrieJvm() {
        val trie = Trie()
        assertFalse(trie.contains(*emptyArray()))

        trie.add(d, "a", "b", "c")
        // empty lookup should still be false after entries exist
        assertFalse(trie.contains(*emptyArray()))
        assertFalse(trie.contains("a"))
        assertFalse(trie.contains("a", "b"))
        assertTrue(trie.contains("a", "b", "c"))

        trie.add(d, "a", "b")
        assertTrue(trie.contains("a", "b"))

        trie.add(d, "a", "b", "d", "e")
        assertTrue(trie.contains("a", "b", "d", "e"))
    }
}
