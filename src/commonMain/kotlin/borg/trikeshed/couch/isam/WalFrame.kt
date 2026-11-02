package borg.trikeshed.couch.isam
/**
 * Common CRC32C algorithm for WAL frame validation and SCTP packets (RFC 9260 Appendix A).
 *
 * [compute] is the target's [crc32c]: the CPU's instruction where the target has one. [software] is what targets
 * without it run (js, wasmJs, wasmWasi, mingwX64, and posix on a CPU without it): slicing-by-8, eight bytes per step
 * through eight 256-entry rows, so the eight loads of a step do not wait on each other the way a byte-at-a-time loop's
 * single chain does. [bytewise] is that one-byte form, the reference the tests hold [software] to.
 */
object Crc32C {
    /** Row 0 is the byte table; row k is row 0 advanced over k more zero bytes. Row k starts at 256 * k. */
    val TABLE = IntArray(8 * 256)
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
        for (i in 0 until 256) {
            var crc = TABLE[i]
            for (k in 1 until 8) {
                crc = TABLE[crc and 0xFF] xor (crc ushr 8)
                TABLE[256 * k + i] = crc
            }
        }
    }
    /** The CRC32c of bytes[offset, offset + length): the target's [crc32c] (the CPU's instruction where it has one). */
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int = crc32c(bytes, offset, length)

    /** The same CRC32c from the table, slicing-by-8: what targets without the instruction run, and what tests hold [crc32c] to. */
    fun software(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        val t = TABLE
        var crc = 0xFFFFFFFF.toInt()
        var at = offset
        val end = offset + length
        val wordsEnd = end - 7
        while (at < wordsEnd) {
            val lo = crc xor ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or (bytes[at + 3].toInt() shl 24))
            val hi = (bytes[at + 4].toInt() and 0xFF) or ((bytes[at + 5].toInt() and 0xFF) shl 8) or
                ((bytes[at + 6].toInt() and 0xFF) shl 16) or (bytes[at + 7].toInt() shl 24)
            crc = t[1792 + (lo and 0xFF)] xor t[1536 + ((lo ushr 8) and 0xFF)] xor
                t[1280 + ((lo ushr 16) and 0xFF)] xor t[1024 + (lo ushr 24)] xor
                t[768 + (hi and 0xFF)] xor t[512 + ((hi ushr 8) and 0xFF)] xor
                t[256 + ((hi ushr 16) and 0xFF)] xor t[hi ushr 24]
            at += 8
        }
        while (at < end) {
            crc = t[(crc xor bytes[at].toInt()) and 0xFF] xor (crc ushr 8)
            at++
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    /** The byte-at-a-time CRC32C: one table lookup and one dependent step per byte. */
    fun bytewise(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        val t = TABLE
        var crc = 0xFFFFFFFF.toInt()
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            crc = t[(crc xor b) and 0xFF] xor (crc ushr 8)
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

 
 
