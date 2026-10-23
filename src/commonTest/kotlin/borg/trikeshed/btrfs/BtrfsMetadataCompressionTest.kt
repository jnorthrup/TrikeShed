package borg.trikeshed.btrfs

import borg.trikeshed.job.sha256
import borg.trikeshed.userspace.btrfs.BtrfsCodecLE
import kotlin.random.Random
import kotlin.test.*

class BtrfsMetadataCompressionTest {
    @Test fun codecsPreserveEmptyRandomAndMultiblockMetadata() {
        val random = Random(17)
        val row = "generation:42; member:a.img; member:b.img;"
        val inputs = arrayOf(ByteArray(0), byteArrayOf(0, -1, 17), random.nextBytes(8192),
            ByteArray(300_000) { row[it % row.length].code.toByte() })
        for (codec in BtrfsMetadataCompression.entries) for (input in inputs) {
            val before = input.copyOf()
            val encoded = codec.encode(input)
            assertTrue(BtrfsMetadataCompression.isEncoded(encoded))
            assertContentEquals(before, input)
            assertContentEquals(input, BtrfsMetadataCompression.decode(encoded))
            assertTrue(encoded.size <= input.size + 64)
        }
        for (codec in BtrfsMetadataCompression.entries)
            assertEquals(BtrfsMetadataCompression.RAW, BtrfsMetadataCompression.storedCompression(codec.encode(byteArrayOf(1))))
    }

    @Test fun zstdAndLz4ActuallyCompressMetadata() {
        val input = fixturePlaintext()
        for (codec in arrayOf(BtrfsMetadataCompression.ZSTD, BtrfsMetadataCompression.LZ4)) {
            val encoded = codec.encode(input)
            assertEquals(codec, BtrfsMetadataCompression.storedCompression(encoded))
            assertTrue(encoded.size < input.size / 4)
        }
    }

    @Test fun decodesIndependentLibzstdAndLiblz4Fixtures() {
        // libzstd 1.5.7 level 3 and liblz4 1.10.0 LZ4_compress_default, respectively.
        // Source: the 68-byte JSON line returned by fixturePlaintext(), repeated 64 times.
        val zstd = hex("28b52ffd600010550200e4037b2267656e65726174696f6e223a372c226d656d62657273223a5b22616c7068612e696d67222c226265745d2c226c61796f7574223a225350414e227d0a020073e1083dcc58d103")
        val lz4 = hex("f21c7b2267656e65726174696f6e223a372c226d656d62657273223a5b22616c7068612e696d67222c226265740b00ff045d2c226c61796f7574223a225350414e227d0a4400ffffffffffffffffffffffffffffffffb450414e227d0a")
        for ((codec, payload) in arrayOf(BtrfsMetadataCompression.ZSTD to zstd, BtrfsMetadataCompression.LZ4 to lz4)) {
            val input = fixturePlaintext()
            assertContentEquals(input, BtrfsMetadataCompression.decode(envelope(codec, input.size, payload)))
        }
    }

    @Test fun validatesLengthsFlagsVersionAndChecksumBeforeDecompression() {
        val valid = BtrfsMetadataCompression.ZSTD.encode(fixturePlaintext())
        for ((offset, value) in arrayOf(8 to 2, 12 to 7, 16 to -1, 16 to Int.MAX_VALUE,
            20 to -1, 20 to Int.MAX_VALUE, 24 to 1, 28 to 1)) {
            val damaged = valid.copyOf()
            BtrfsCodecLE.writeInt(value, damaged, offset)
            digest(damaged)
            assertFails("offset=$offset value=$value") { BtrfsMetadataCompression.decode(damaged) }
        }
        for (index in 8 until valid.size) {
            val damaged = valid.copyOf()
            damaged[index] = (damaged[index].toInt() xor 1).toByte()
            assertFails("byte=$index") { BtrfsMetadataCompression.decode(damaged) }
        }
        for (length in 8 until valid.size)
            assertFails("truncated at $length") { BtrfsMetadataCompression.decode(valid.copyOf(length)) }
        assertFails { BtrfsMetadataCompression.decode(valid + byteArrayOf(0)) }
    }

    @Test fun rejectsWrongDecodedLengthAndTrailingCompressedBytesWithValidEnvelopeChecksums() {
        for (codec in arrayOf(BtrfsMetadataCompression.ZSTD, BtrfsMetadataCompression.LZ4)) {
            val plain = fixturePlaintext()
            val encoded = codec.encode(plain)
            val payload = encoded.copyOfRange(32, encoded.size - 32)
            assertFails { BtrfsMetadataCompression.decode(envelope(codec, plain.size - 1, payload)) }
            assertFails { BtrfsMetadataCompression.decode(envelope(codec, plain.size + 1, payload)) }
            assertFails { BtrfsMetadataCompression.decode(envelope(codec, plain.size, payload + byteArrayOf(0))) }
        }
        val stored = envelope(BtrfsMetadataCompression.RAW, 4, byteArrayOf(1, 2, 3))
        assertFails { BtrfsMetadataCompression.decode(stored) }
    }

    @Test fun appliesCallerLimitToLegacyBytesAndEnvelopes() {
        val plain = fixturePlaintext()
        val encoded = BtrfsMetadataCompression.LZ4.encode(plain)
        assertFails { BtrfsMetadataCompression.decode(encoded, plain.size - 1) }
        assertFails { BtrfsMetadataCompression.decode(plain, plain.size - 1) }
        assertFails { BtrfsMetadataCompression.RAW.encode(plain, plain.size - 1) }
        assertFails { BtrfsMetadataCompression.decode(encoded, -1) }
        assertFails { BtrfsMetadataCompression.decode(encoded, Int.MAX_VALUE) }
        assertContentEquals(plain, BtrfsMetadataCompression.decode(encoded, plain.size))
        val legacy = BtrfsMetadataCompression.decode(plain)
        assertContentEquals(plain, legacy)
        legacy[0] = 0
        assertNotEquals(legacy[0], plain[0])
    }

    private fun fixturePlaintext(): ByteArray =
        ("{\"generation\":7,\"members\":[\"alpha.img\",\"beta.img\"],\"layout\":\"SPAN\"}\n".repeat(64)).encodeToByteArray()

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun envelope(codec: BtrfsMetadataCompression, decodedSize: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArray(64 + payload.size)
        "TSMETA01".encodeToByteArray().copyInto(bytes)
        BtrfsCodecLE.writeInt(1, bytes, 8)
        BtrfsCodecLE.writeInt(codec.code, bytes, 12)
        BtrfsCodecLE.writeInt(decodedSize, bytes, 16)
        BtrfsCodecLE.writeInt(payload.size, bytes, 20)
        payload.copyInto(bytes, 32)
        digest(bytes)
        return bytes
    }

    private fun digest(bytes: ByteArray) {
        val end = bytes.size - 32
        sha256(bytes.copyOfRange(0, end)).copyInto(bytes, end)
    }
}
