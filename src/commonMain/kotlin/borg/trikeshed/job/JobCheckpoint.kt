package borg.trikeshed.job

data class JobCheckpoint(
    val committedSequence: Long,
    val rootCid: ContentId,
    val schemaCid: ContentId,
    val metadata: Map<String, ContentId>
)

object JobCheckpointCodec {
    private val MAGIC = byteArrayOf(0x4A, 0x43, 0x31, 0x00) // JC1\0
    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024

    fun encode(checkpoint: JobCheckpoint): ByteArray {
        val out = mutableListOf<Byte>()
        out.addAll(MAGIC.toList())
        out.addAll(intToBytes(VERSION).toList())

        out.addAll(longToBytes(checkpoint.committedSequence).toList())
        encodeString(checkpoint.rootCid.value, out)
        encodeString(checkpoint.schemaCid.value, out)

        out.addAll(intToBytes(checkpoint.metadata.size).toList())
        for ((key, cid) in checkpoint.metadata) {
            encodeString(key, out)
            encodeString(cid.value, out)
        }

        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): JobCheckpoint? = runCatching {
        var offset = 0
        fun readBytes(size: Int): ByteArray {
            if (offset + size > bytes.size) throw IllegalArgumentException("Unexpected end of bytes")
            val res = bytes.copyOfRange(offset, offset + size)
            offset += size
            return res
        }
        fun readInt(): Int {
            val b = readBytes(4)
            return ((b[0].toInt() and 0xFF) shl 24) or
                   ((b[1].toInt() and 0xFF) shl 16) or
                   ((b[2].toInt() and 0xFF) shl 8) or
                   (b[3].toInt() and 0xFF)
        }
        fun readLong(): Long {
            val b = readBytes(8)
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (b[i].toLong() and 0xFFL)
            }
            return value
        }
        fun readString(): String {
            val size = readInt()
            if (size > MAX_FIELD_BYTES) throw IllegalArgumentException("Field size too large")
            return readBytes(size).decodeToString()
        }

        val magic = readBytes(4)
        if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("Invalid magic bytes")
        val version = readInt()
        if (version != VERSION) throw IllegalArgumentException("Unsupported version")

        val committedSequence = readLong()
        val rootCid = ContentId(readString())
        val schemaCid = ContentId(readString())

        val metadataSize = readInt()
        val metadata = mutableMapOf<String, ContentId>()
        for (i in 0 until metadataSize) {
            val key = readString()
            val cid = ContentId(readString())
            metadata[key] = cid
        }

        JobCheckpoint(committedSequence, rootCid, schemaCid, metadata)
    }.getOrNull()

    private fun encodeString(str: String, out: MutableList<Byte>) {
        val bytes = str.encodeToByteArray()
        out.addAll(intToBytes(bytes.size).toList())
        out.addAll(bytes.toList())
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value ushr 56).toByte(),
            (value ushr 48).toByte(),
            (value ushr 40).toByte(),
            (value ushr 32).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }
}
