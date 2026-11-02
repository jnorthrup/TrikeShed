package borg.trikeshed.sctp

import borg.trikeshed.couch.isam.Crc32C
import borg.trikeshed.couch.isam.crc32c
import borg.trikeshed.lib.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [crc32c] is the target's CRC32c (the CPU's instruction where the target has one: SSE4.2, the arm64 CRC extension, the
 * JVM's intrinsic); [Crc32C.software] (slicing-by-8) is the table it must equal on every input, and [Crc32C.bytewise]
 * and a table-free bit loop are what [Crc32C.software] is held to.
 */
class Crc32cTest {
    /** CRC32C one bit at a time: no table, so nothing here can share a table mistake with [Crc32C]. */
    fun bitwise(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = crc xor (bytes[i].toInt() and 0xff)
            for (bit in (0..7).toSeries().view) crc = if (crc and 1 != 0) (crc ushr 1) xor 0x82f63b78.toInt() else crc ushr 1
        }
        return crc.inv()
    }

    /** RFC 3720 §B.4: the ASCII digits 1 to 9, 32 zero bytes, 32 bytes of 0xFF, 32 rising bytes, 32 falling bytes. */
    @Test
    fun matchesTheRfcVectors() {
        val digits = "123456789".encodeToByteArray()
        assertEquals(0xE3069283.toInt(), crc32c(digits, 0, digits.size))
        assertEquals(0x8A9136AA.toInt(), crc32c(ByteArray(32), 0, 32))
        assertEquals(0x62A8AB43, crc32c(ByteArray(32) { -1 }, 0, 32))
        assertEquals(0x46DD794E, crc32c(ByteArray(32) { it.toByte() }, 0, 32))
        assertEquals(0x113FDB5C, crc32c(ByteArray(32) { (31 - it).toByte() }, 0, 32))
        assertEquals(0, crc32c(ByteArray(0), 0, 0))
    }

    /** Every length to 300 at offsets 0 to 9 (each alignment of an eight-byte step), and packet sizes to 9,000. */
    @Test
    fun equalsTheTableOnEveryLengthAndOffset() {
        var x = 0x9E3779B97F4A7C15uL.toLong()
        val bytes = ByteArray(9_100) { x = x * 6364136223846793005L + 1442695040888963407L; (x ushr 56).toByte() }
        for (offset in 0 until 10) for (length in 0..300)
            assertEquals(Crc32C.software(bytes, offset, length), crc32c(bytes, offset, length), "offset $offset length $length")
        for (length in intArrayOf(1452, 1500, 4096, 8999, 9_000))
            assertEquals(Crc32C.software(bytes, 7, length), crc32c(bytes, 7, length), "length $length")
        assertEquals(Crc32C.software(bytes, bytes.size, 0), crc32c(bytes, bytes.size, 0), "an empty range at the end")
    }

    /** The checksum SCTP packets carry goes through the same function. */
    @Test
    fun computeIsTheTargetFunction() {
        val bytes = ByteArray(1452) { (it * 7).toByte() }
        assertEquals(crc32c(bytes, 3, 1400), Crc32C.compute(bytes, 3, 1400))
    }

    /** Every length 0 to 4096 at five start offsets, over random bytes: slicing-by-8 equals the byte-at-a-time form. */
    @Test
    fun softwareMatchesTheByteWiseFormAtEveryLengthAndAlignment() {
        val random = Random(0x5c7b)
        val data = ByteArray(4096 + 16).also { random.nextBytes(it) }
        for (length in (0..4096).toSeries().view) {
            for (offset in _a[0, 1, 3, 7, 8].toList()) {
                assertEquals(Crc32C.bytewise(data, offset, length), Crc32C.software(data, offset, length), "length $length offset $offset")
            }
        }
    }

    /** The bit loop agrees with the table and the target on short inputs of every length (it is 8 times slower, so it stops at 300). */
    @Test
    fun matchesTheBitLoop() {
        val random = Random(0x1e55)
        val data = ByteArray(300 + 8).also { random.nextBytes(it) }
        for (length in (0..300).toSeries().view) {
            for (offset in _a[0, 3, 5].toList()) {
                assertEquals(bitwise(data, offset, length), Crc32C.software(data, offset, length), "software length $length offset $offset")
                assertEquals(bitwise(data, offset, length), crc32c(data, offset, length), "target length $length offset $offset")
            }
        }
    }

    /** A run of 0xff and a run of 0x00 reach every table row through carries; a sign-extended byte must not leak into the next. */
    @Test
    fun highBitBytesDoNotLeakAcrossLanes() {
        for (fill in _a[0x00, 0x7f, 0x80, 0xff].toList()) {
            val data = ByteArray(1500) { fill.toByte() }
            for (length in _a[1, 7, 8, 9, 15, 16, 17, 1452, 1500].toList()) {
                assertEquals(Crc32C.bytewise(data, 0, length), Crc32C.software(data, 0, length), "fill $fill length $length")
            }
        }
    }
}
