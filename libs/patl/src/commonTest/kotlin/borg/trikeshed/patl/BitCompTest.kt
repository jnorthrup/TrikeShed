package borg.trikeshed.patl

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.*

class BitCompTest {
    private val stringBytes: (String) -> Series<Byte> = { s ->
        s.length j { i -> s[i].code.toByte() }
    }

    @Test
    fun `identical strings return ALL_MATCH`() {
        val bc = BitComp(stringBytes)
        assertTrue(bc.mismatch("hello", "hello") == BitComp.ALL_MATCH)
    }

    @Test
    fun `first differing byte produces bit offset`() {
        val bc = BitComp(stringBytes)
        // "abc" vs "abd": byte 2 differs, 'c'=0x63 vs 'd'=0x64 = xor 0x07
        // first 1-bit in 0x07 is bit 0 → byte 2 * 8 + 0 = 16
        assertTrue(bc.mismatch("abc", "abd") == 16u)
    }

    @Test
    fun `prefix mismatch returns bit past shorter key`() {
        val bc = BitComp(stringBytes)
        // "ab" (16 bits) vs "abc" (24 bits): prefix matches entirely
        assertTrue(bc.mismatch("ab", "abc") == 16u)
    }

    @Test
    fun `first byte differs immediately`() {
        val bc = BitComp(stringBytes)
        // "x"=0x78 vs "y"=0x79: xor 0x01, bit 0 → bit 0
        assertTrue(bc.mismatch("x", "y") == 0u)
    }

    @Test
    fun `empty vs nonempty returns bit 0`() {
        val bc = BitComp(stringBytes)
        // empty (0 bits) vs "a" (8 bits): mismatch at bit 0
        assertTrue(bc.mismatch("", "a") == 0u)
    }

    @Test
    fun `mid-byte bit mismatch at bit 5`() {
        // 0x20 (00100000) vs 0x00 (00000000): xor 0x20, bit 5
        val exA: (String) -> Series<Byte> = { s -> 1 j { i -> s[i].code.toByte() } }
        val bc = BitComp(exA)
        val a = charArrayOf(0x20.toChar()).concatToString()
        val b = charArrayOf(0x00.toChar()).concatToString()
        assertTrue(bc.mismatch(a, b) == 5u)
    }
}
