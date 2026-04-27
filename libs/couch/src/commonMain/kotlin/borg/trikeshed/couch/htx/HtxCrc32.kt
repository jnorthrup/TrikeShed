package borg.trikeshed.couch.htx

/**
 * CRC32 (IEEE 802.3 / PKZIP / Ethernet polynomial) — pure Kotlin, no platform deps.
 *
 * Polynomial: 0xEDB88320 (reflected 0x04C11DB7)
 * Used for HTX message framing checksums.
 */
object HtxCrc32 {
    // Pre-computed lookup table (256 entries, reflected polynomial)
    private val TABLE: IntArray by lazy {
        IntArray(256) { n ->
            var c = n
            for (k in 0 until 8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
            c
        }
    }

    /** Compute CRC32 over a ByteArray. Returns unsigned 32-bit value. */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): UInt {
        var crc = 0xFFFFFFFF.toInt()
        val table = TABLE
        for (i in offset until offset + length) {
            val idx = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc ushr 8) xor table[idx]
        }
        return (crc xor 0xFFFFFFFF.toInt()).toUInt()
    }

    /** Compute CRC32 over a sequence of ByteArrays (streaming). */
    fun computeStream(blocks: Sequence<ByteArray>): UInt {
        var crc = 0xFFFFFFFF.toInt()
        val table = TABLE
        for (block in blocks) {
            for (b in block) {
                val idx = (crc xor (b.toInt() and 0xFF)) and 0xFF
                crc = (crc ushr 8) xor table[idx]
            }
        }
        return (crc xor 0xFFFFFFFF.toInt()).toUInt()
    }
}
