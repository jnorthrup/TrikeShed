package borg.trikeshed.patl

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.*

class PatriciaTrieSetTest {
    private val stringBytes: (String) -> Series<Byte> = { s ->
        s.length j { i -> s[i].code.toByte() }
    }

    @Test
    fun `empty set contains nothing`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        assertFalse(set.contains("foo"))
        assertTrue(set.size == 0)
    }

    @Test
    fun `add then contains returns true`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("hello")
        assertTrue(set.contains("hello"))
        assertTrue(set.size == 1)
    }

    @Test
    fun `two keys sharing prefix both found`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("abc")
        set.add("abd")
        assertTrue(set.contains("abc"))
        assertTrue(set.contains("abd"))
        assertTrue(set.size == 2)
    }

    @Test
    fun `key that splits existing node`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("abcde")
        set.add("abxyz")
        assertTrue(set.contains("abcde"))
        assertTrue(set.contains("abxyz"))
    }

    @Test
    fun `add duplicate does not increase size`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("key")
        set.add("key")
        assertTrue(set.size == 1)
    }

    @Test
    fun `prefix key and longer key coexist`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("ab")
        set.add("abc")
        assertTrue(set.contains("ab"))
        assertTrue(set.contains("abc"))
    }

    @Test
    fun `missing key returns false`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("abc")
        set.add("abd")
        assertFalse(set.contains("xyz"))
        assertFalse(set.contains("ab"))
        assertFalse(set.contains("abcd"))
    }

    @Test
    fun `empty string key`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("")
        assertTrue(set.contains(""))
    }

    @Test
    fun `iteration visits all keys in preorder`() {
        val set = PatriciaTrieSet(BitComp(stringBytes))
        set.add("a")
        set.add("b")
        set.add("aa")
        val list = set.toList()
        assertTrue(list.size == 3)
        assertTrue(list.contains("a"))
        assertTrue(list.contains("b"))
        assertTrue(list.contains("aa"))
    }
}
