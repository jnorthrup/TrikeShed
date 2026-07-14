package borg.trikeshed.couch.isam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I1 RED — WAL frame format contract.
 *
 * The plan: "magic | version | sequence | payloadLength | canonical Confix
 * event bytes | crc32c"
 *
 * Tests prove the exact byte layout and CRC32C integrity.
 */
class WalFrameFormatTest {

    @Test
    fun frameHasCorrectByteLayout() {
        val frame = WalFrame.encode(
            sequence = 42L,
            payload = "test-payload".encodeToByteArray(),
        )

        // Layout: magic(4) | version(2) | sequence(8) | payloadLength(4) | payload(n) | crc32c(4)
        var offset = 0

        // Magic: 4 bytes
        val magic = WalFrame.MAGIC
        for (i in 0 until 4) {
            assertEquals(magic[i], frame[i],
                "magic byte $i must match: ${magic[i]} vs ${frame[i]}")
        }
        offset += 4

        // Version: 2 bytes
        val version = ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)
        assertTrue(version >= 1, "version must be ≥1")
        offset += 2

        // Sequence: 8 bytes (big-endian long)
        var seq = 0L
        for (i in 0 until 8) {
            seq = (seq shl 8) or (frame[offset + i].toLong() and 0xFF)
        }
        assertEquals(42L, seq, "sequence must be 42")
        offset += 8

        // PayloadLength: 4 bytes (big-endian int)
        var len = 0
        for (i in 0 until 4) {
            len = (len shl 8) or (frame[offset + i].toInt() and 0xFF)
        }
        assertEquals("test-payload".encodeToByteArray().size, len,
            "payloadLength must match")
        offset += 4

        // Payload starts at offset
        assertEquals("test-payload", frame.sliceArray(offset until offset + len).decodeToString())
    }

    @Test
    fun crc32cValidatesCorrectly() {
        val payload = "good".encodeToByteArray()
        val frame = WalFrame.encode(sequence = 1L, payload = payload)

        assertTrue(WalFrame.validate(frame), "valid frame must pass CRC32C")
    }

    @Test
    fun crc32cDetectsCorruption() {
        val frame = WalFrame.encode(sequence = 1L, payload = "good".encodeToByteArray())

        // Flip a byte in the payload
        val corrupted = frame.copyOf()
        corrupted[corrupted.size - 5] = (corrupted[corrupted.size - 5].toInt() xor 0xFF).toByte()

        assertTrue(!WalFrame.validate(corrupted), "corrupted frame must fail CRC32C")
    }

    @Test
    fun partialHeaderRejected() {
        // Too short for a valid header
        val partial = byteArrayOf(0, 1, 2, 3)
        assertTrue(!WalFrame.validate(partial), "partial header must fail validation")
    }
}
