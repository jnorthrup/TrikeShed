package borg.trikeshed.couch.isam

/**
 * Common CRC32C algorithm for WAL frame validation.
 */
object Crc32C {
    private val TABLE = IntArray(256)

    init {
        val poly = 0x82f63b78.toInt()
        for (i in 0 until 256) {
            var crc = i
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor poly
                } else {
                    crc ushr 1
                }
            }
            TABLE[i] = crc
        }
    }

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            crc = TABLE[(crc xor b) and 0xFF] xor (crc ushr 8)
        }
        return crc xor 0xFFFFFFFF.toInt()
    }
}

object WalFrame {
    val MAGIC = byteArrayOf(0x57, 0x41, 0x4C, 0x31) // "WAL1"
    const val VERSION = 1
    const val HEADER_SIZE = 18 // 4(magic) + 2(version) + 8(sequence) + 4(payloadLength)

    fun encode(sequence: Long, payload: ByteArray): ByteArray {
        val len = payload.size
        val out = ByteArray(HEADER_SIZE + len + 4)
        var offset = 0

        // Magic
        MAGIC.copyInto(out, offset)
        offset += 4

        // Version
        out[offset++] = (VERSION ushr 8).toByte()
        out[offset++] = VERSION.toByte()

        // Sequence
        for (i in 7 downTo 0) {
            out[offset++] = (sequence ushr (i * 8)).toByte()
        }

        // PayloadLength
        for (i in 3 downTo 0) {
            out[offset++] = (len ushr (i * 8)).toByte()
        }

        // Payload
        payload.copyInto(out, offset)

        // CRC32C over the whole frame except the CRC field itself
        val crc = Crc32C.compute(out, 0, HEADER_SIZE + len)
        offset += len

        // CRC
        for (i in 3 downTo 0) {
            out[offset++] = (crc ushr (i * 8)).toByte()
        }

        return out
    }

    fun validate(frame: ByteArray): Boolean {
        if (frame.size < HEADER_SIZE + 4) return false

        // Check magic
        for (i in 0 until 4) {
            if (frame[i] != MAGIC[i]) return false
        }

        // Read payload length
        var len = 0
        for (i in 0 until 4) {
            len = (len shl 8) or (frame[14 + i].toInt() and 0xFF)
        }

        if (frame.size != HEADER_SIZE + len + 4) return false

        // Check CRC
        val expectedCrc = Crc32C.compute(frame, 0, HEADER_SIZE + len)
        var actualCrc = 0
        val offset = HEADER_SIZE + len
        for (i in 0 until 4) {
            actualCrc = (actualCrc shl 8) or (frame[offset + i].toInt() and 0xFF)
        }

        return expectedCrc == actualCrc
    }
}
