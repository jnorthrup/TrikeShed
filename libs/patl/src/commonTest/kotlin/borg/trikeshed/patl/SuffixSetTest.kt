package borg.trikeshed.patl

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.*

class SuffixSetTest {
    private val stringBytes: (String) -> Series<Byte> = { s ->
        s.length j { i -> s[i].code.toByte() }
    }

    @Test
    fun `empty string has one suffix`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("")
        assertTrue(ss.size == 1)
        assertTrue(ss.contains(""))
    }

    @Test
    fun `single char has one suffix`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("a")
        assertTrue(ss.size == 1)
        assertTrue(ss.contains("a"))
    }

    @Test
    fun `two distinct chars produce two suffixes`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("ab")
        assertTrue(ss.size == 2)
        assertTrue(ss.contains("ab"))
        assertTrue(ss.contains("b"))
    }

    @Test
    fun `repeated char produces correct suffix count`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("aaa")
        assertTrue(ss.size == 3)
        assertTrue(ss.contains("aaa"))
        assertTrue(ss.contains("aa"))
        assertTrue(ss.contains("a"))
    }

    @Test
    fun `missing suffix returns false`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("hello")
        assertFalse(ss.contains("world"))
        assertFalse(ss.contains("lo"))
    }

    @Test
    fun `substring that is not a suffix returns false`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("abc")
        assertTrue(ss.contains("abc"))
        assertTrue(ss.contains("bc"))
        assertTrue(ss.contains("c"))
        assertFalse(ss.contains("ab"))
        assertFalse(ss.contains("a"))
    }

    @Test
    fun `iteration yields all suffixes`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("xyz")
        val list = ss.toList()
        assertTrue(list.size == 3)
        assertTrue(list.contains("xyz"))
        assertTrue(list.contains("yz"))
        assertTrue(list.contains("z"))
    }

    @Test
    fun `multiple insertions accumulate suffixes`() {
        val ss = SuffixSet(BitComp(stringBytes))
        ss.insert("ab")
        ss.insert("cd")
        assertTrue(ss.size == 4)
        assertTrue(ss.contains("ab"))
        assertTrue(ss.contains("b"))
        assertTrue(ss.contains("cd"))
        assertTrue(ss.contains("d"))
    }
}
