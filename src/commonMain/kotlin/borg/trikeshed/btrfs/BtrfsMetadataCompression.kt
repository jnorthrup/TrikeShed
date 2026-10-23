package borg.trikeshed.btrfs

import borg.trikeshed.job.sha256
import borg.trikeshed.tilting.lz4.Lz4Block
import borg.trikeshed.tilting.lz4.Lz4Kotlin
import borg.trikeshed.tilting.zran.ZstdDecoder
import borg.trikeshed.tilting.zran.ZstdEncoder
import borg.trikeshed.userspace.btrfs.BtrfsCodecLE

/**
 * Compression of userspace manifests, volume-generation records, and the
 * captured native tree bytes in [BtrfsMetadataVolume] sidecars. These envelopes
 * are a backing-store format: native Btrfs metadata remains uncompressed at the
 * logical image boundary presented to filesystem tools.
 *
 * The versioned envelope holds little-endian codec and length fields followed by
 * its payload and a SHA-256 of the complete header and payload. Codec instances
 * are request-local; calls do not share mutable compressor scratch.
 */
enum class BtrfsMetadataCompression(val code: Int) {
    RAW(0),
    ZSTD(1),
    LZ4(2);

    /** Always writes a checked envelope; incompressible input uses a RAW payload. */
    fun encode(bytes: ByteArray, maxDecodedBytes: Int = MAX_BYTES): ByteArray {
        limit(maxDecodedBytes)
        require(bytes.size <= maxDecodedBytes) { "Userspace metadata exceeds its size limit" }
        val compressed = when (this) {
            RAW -> bytes
            ZSTD -> ZstdEncoder().compress(bytes)
            LZ4 -> {
                val target = ByteArray(Lz4Block.bound(bytes.size))
                val size = Lz4Kotlin().compress(bytes, 0, bytes.size, target, 0)
                target.copyOf(size)
            }
        }
        val codec = if (compressed.size < bytes.size) this else RAW
        val payload = if (codec == RAW) bytes else compressed
        val encoded = ByteArray(HEADER_BYTES + payload.size + DIGEST_BYTES)
        MAGIC.copyInto(encoded)
        BtrfsCodecLE.writeInt(VERSION, encoded, 8)
        BtrfsCodecLE.writeInt(codec.code, encoded, 12)
        BtrfsCodecLE.writeInt(bytes.size, encoded, 16)
        BtrfsCodecLE.writeInt(payload.size, encoded, 20)
        payload.copyInto(encoded, HEADER_BYTES)
        val end = HEADER_BYTES + payload.size
        sha256(encoded.copyOfRange(0, end)).copyInto(encoded, end)
        return encoded
    }

    companion object {
        const val MAX_BYTES: Int = 64 * 1024 * 1024
        const val HEADER_BYTES: Int = 32
        const val DIGEST_BYTES: Int = 32
        private const val VERSION: Int = 1
        private val MAGIC = "TSMETA01".encodeToByteArray()

        fun isEncoded(bytes: ByteArray): Boolean =
            bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

        /** Validates the envelope without decoding its payload; null denotes legacy raw bytes. */
        fun storedCompression(bytes: ByteArray, maxDecodedBytes: Int = MAX_BYTES): BtrfsMetadataCompression? {
            limit(maxDecodedBytes)
            if (!isEncoded(bytes)) {
                require(bytes.size <= maxDecodedBytes) { "Userspace metadata exceeds its size limit" }
                return null
            }
            require(bytes.size >= HEADER_BYTES + DIGEST_BYTES) { "Truncated userspace metadata envelope" }
            require(BtrfsCodecLE.readInt(bytes, 8) == VERSION) { "Unsupported userspace metadata version" }
            require(BtrfsCodecLE.readInt(bytes, 24) == 0 && BtrfsCodecLE.readInt(bytes, 28) == 0) {
                "Unknown userspace metadata flags"
            }
            val code = BtrfsCodecLE.readInt(bytes, 12)
            val codec = entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown userspace metadata codec $code")
            val decodedSize = BtrfsCodecLE.readInt(bytes, 16)
            val payloadSize = BtrfsCodecLE.readInt(bytes, 20)
            require(decodedSize in 0..maxDecodedBytes && payloadSize in 0..maxDecodedBytes) {
                "Userspace metadata exceeds its size limit"
            }
            require(payloadSize == bytes.size - HEADER_BYTES - DIGEST_BYTES) {
                "Userspace metadata payload length mismatch"
            }
            require(codec != RAW || decodedSize == payloadSize) { "RAW metadata length mismatch" }
            val end = HEADER_BYTES + payloadSize
            require(sha256(bytes.copyOfRange(0, end)).contentEquals(bytes.copyOfRange(end, bytes.size))) {
                "Userspace metadata checksum mismatch"
            }
            return codec
        }

        /**
         * Reads either an envelope or earlier unframed metadata. Bounds and the
         * envelope checksum are checked before allocating a decoded destination.
         * Readers do not need to know the codec selected by the previous writer.
         */
        fun decode(bytes: ByteArray, maxDecodedBytes: Int = MAX_BYTES): ByteArray {
            val codec = storedCompression(bytes, maxDecodedBytes) ?: return bytes.copyOf()
            val decodedSize = BtrfsCodecLE.readInt(bytes, 16)
            val payloadSize = BtrfsCodecLE.readInt(bytes, 20)
            val end = HEADER_BYTES + payloadSize
            if (codec == RAW) return bytes.copyOfRange(HEADER_BYTES, end)
            val decoded = ByteArray(decodedSize)
            when (codec) {
                ZSTD -> require(ZstdDecoder().decodeInto(bytes, HEADER_BYTES, end, decoded, 0, decodedSize) == end) {
                    "Trailing bytes in Zstd metadata payload"
                }
                LZ4 -> require(Lz4Kotlin().decompress(bytes, HEADER_BYTES, payloadSize, decoded, 0, decodedSize) == decodedSize) {
                    "LZ4 metadata decoded length mismatch"
                }
                RAW -> error("RAW metadata was decoded above")
            }
            return decoded
        }

        private fun limit(maxDecodedBytes: Int) {
            require(maxDecodedBytes in 0..MAX_BYTES) { "Invalid userspace metadata size limit" }
        }
    }
}
