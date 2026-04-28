package borg.trikeshed.patl

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.*

class SuffixMapTest {
    private val stringBytes: (String) -> Series<Byte> = { s ->
        s.length j { i -> s[i].code.toByte() }
    }

    @Test
    fun `empty map returns null for any key`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        assertNull(sm["any"])
    }

    @Test
    fun `single suffix insertion retrievable`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        sm.insert("abc", 42)
        assertTrue(sm["abc"] == 42)
    }

    @Test
    fun `all suffixes of a string get same value`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        sm.insertString("ab", 1)
        assertTrue(sm["ab"] == 1)
        assertTrue(sm["b"] == 1)
    }

    @Test
    fun `overwrite suffix value`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        sm.insert("abc", 1)
        sm.insert("abc", 99)
        assertTrue(sm["abc"] == 99)
    }

    @Test
    fun `multiple string insertions accumulate`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        sm.insertString("ab", 10)
        sm.insertString("cd", 20)
        assertTrue(sm["ab"] == 10)
        assertTrue(sm["b"] == 10)
        assertTrue(sm["cd"] == 20)
        assertTrue(sm["d"] == 20)
        assertTrue(sm.size == 4)
    }

    @Test
    fun `missing suffix returns null`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        sm.insertString("hello", 1)
        assertNull(sm["world"])
        assertNull(sm["lo"])
    }

    @Test
    fun `size reflects distinct suffixes`() {
        val sm = SuffixMap<String, Int>(BitComp(stringBytes))
        sm.insertString("aaa", 1)
        assertTrue(sm.size == 3)
        sm.insertString("a", 2)
        assertTrue(sm.size == 3)
    }
}
