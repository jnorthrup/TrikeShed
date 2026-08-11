package borg.trikeshed.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HexTest {

    @Test
    fun empty_bytes_produce_empty_string() {
        assertEquals("", ByteArray(0).toLowerHex())
        assertEquals("", ByteArray(0).toUpperHex())
        assertEquals("", hex(ByteArray(0)))
    }

    @Test
    fun single_byte_zero() {
        assertEquals("00", byteArrayOf(0).toLowerHex())
        assertEquals("00", byteArrayOf(0).toUpperHex())
    }

    @Test
    fun single_byte_full_range() {
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toLowerHex())
        assertEquals("FF", byteArrayOf(0xFF.toByte()).toUpperHex())
    }

    @Test
    fun two_bytes_mixed_case() {
        val b = byteArrayOf(0x1A.toByte(), 0x2B.toByte())
        assertEquals("1a2b", b.toLowerHex())
        assertEquals("1A2B", b.toUpperHex())
    }

    @Test
    fun hex_alias_matches_toLowerHex() {
        val b = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals(b.toLowerHex(), hex(b))
        assertEquals("deadbeef", hex(b))
    }

    @Test
    fun sha256_sized_array_is_64_chars() {
        val b = ByteArray(32) { it.toByte() }
        assertEquals(64, b.toLowerHex().length)
        assertEquals(64, b.toUpperHex().length)
    }

    @Test
    fun high_bit_bytes_use_unsigned_shift() {
        // 0x80 → "80", not "ffffff80" or "-80"
        assertEquals("80", byteArrayOf(0x80.toByte()).toLowerHex())
        assertEquals("80", byteArrayOf(0x80.toByte()).toUpperHex())
    }
}
