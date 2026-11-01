package borg.trikeshed.sctp

import borg.trikeshed.couch.isam.Crc32C
import borg.trikeshed.couch.isam.crc32c
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [crc32c] is the target's CRC32c (the CPU's instruction where the target has one: SSE4.2, the arm64 CRC extension, the
 * JVM's intrinsic); [Crc32C.software] is the table it must equal on every input.
 */
class Crc32cTest {
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
}
