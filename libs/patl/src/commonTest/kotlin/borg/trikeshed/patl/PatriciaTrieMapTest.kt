package borg.trikeshed.patl

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.*

class PatriciaTrieMapTest {
    private val stringBytes: (String) -> Series<Byte> = { s ->
        s.length j { i -> s[i].code.toByte() }
    }

    @Test
    fun `empty trie returns null for any key`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        assertNull(trie["foo"])
    }

    @Test
    fun `single insert then get returns value`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["hello"] = 42
        assertEquals(42, trie["hello"])
    }

    @Test
    fun `two keys sharing prefix both retrievable`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["abc"] = 1
        trie["abd"] = 2
        assertEquals(1, trie["abc"])
        assertEquals(2, trie["abd"])
    }

    @Test
    fun `key that splits existing node both retrievable`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["abcde"] = 1
        trie["abxyz"] = 2
        // "abcde" and "abxyz" share prefix "ab", split at "c" vs "x"
        assertEquals(1, trie["abcde"])
        assertEquals(2, trie["abxyz"])
    }

    @Test
    fun `overwrite existing key`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["key"] = 1
        trie["key"] = 99
        assertEquals(99, trie["key"])
    }

    @Test
    fun `prefix key and longer key both stored`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["ab"] = 1
        trie["abc"] = 2
        assertEquals(1, trie["ab"])
        assertEquals(2, trie["abc"])
    }

    @Test
    fun `size reflects insertions`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        assertEquals(0, trie.size)
        trie["a"] = 1
        assertEquals(1, trie.size)
        trie["b"] = 2
        assertEquals(2, trie.size)
        trie["a"] = 3  // overwrite, size unchanged
        assertEquals(2, trie.size)
    }

    @Test
    fun `key not in trie returns null`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["abc"] = 1
        trie["abd"] = 2
        assertNull(trie["xyz"])
        assertNull(trie["ab"])
        assertNull(trie["abcd"])
    }

    @Test
    fun `empty key gets and sets`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie[""] = 99
        assertEquals(99, trie[""])
    }
}
