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
        assertTrue(trie["hello"] == 42)
    }

    @Test
    fun `two keys sharing prefix both retrievable`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["abc"] = 1
        trie["abd"] = 2
        assertTrue(trie["abc"] == 1)
        assertTrue(trie["abd"] == 2)
    }

    @Test
    fun `key that splits existing node both retrievable`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["abcde"] = 1
        trie["abxyz"] = 2
        assertTrue(trie["abcde"] == 1)
        assertTrue(trie["abxyz"] == 2)
    }

    @Test
    fun `overwrite existing key`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["key"] = 1
        trie["key"] = 99
        assertTrue(trie["key"] == 99)
    }

    @Test
    fun `prefix key and longer key both stored`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        trie["ab"] = 1
        trie["abc"] = 2
        assertTrue(trie["ab"] == 1)
        assertTrue(trie["abc"] == 2)
    }

    @Test
    fun `size reflects insertions`() {
        val trie = PatriciaTrieMap<String, Int>(BitComp(stringBytes))
        assertTrue(trie.size == 0)
        trie["a"] = 1
        assertTrue(trie.size == 1)
        trie["b"] = 2
        assertTrue(trie.size == 2)
        trie["a"] = 3
        assertTrue(trie.size == 2)
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
        assertTrue(trie[""] == 99)
    }
}
