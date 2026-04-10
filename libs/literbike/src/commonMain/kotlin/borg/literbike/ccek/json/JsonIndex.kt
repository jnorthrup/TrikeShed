package borg.literbike.ccek.json

/**
 * JsonIndex - Offset encoding from TrikeShed JsonIndex.kt
 *
 * 2-bit encoding scheme for offsets:
 * - 00: Short offset (6 bits, 0-63)
 * - 01: UShort extension (6 bits + 8 bits, 0-16383)
 * - 10: ULong64 extension (6 bits + 16 bits, 0-4194303)
 * - 11: Reserved
 */

/** Offset encoding types (2 bits in high nibble) */
enum class OffsetEncoding(val value: Int) {
    Short(0),      // 6 bits: 0-63
    UShort(1),     // 6+8 bits: 0-16383
    ULong(2),      // 6+16 bits: 0-4194303
    Reserved(3);

    companion object {
        /** Encode offset with encoding type */
        fun encode(offset: Long): ByteArray {
            return when (offset) {
                in 0..63 -> {
                    byteArrayOf(offset.toByte())
                }
                in 64..16383 -> {
                    val shortPart = ((offset shr 8).toInt() or 0x40).toByte() // UShort marker
                    val longPart = (offset and 0xFF).toByte()
                    byteArrayOf(shortPart, longPart)
                }
                in 16384..4194303 -> {
                    val marker = ((offset shr 16).toInt() or 0x80).toByte() // ULong marker
                    val mid = ((offset shr 8) and 0xFF).toByte()
                    val low = (offset and 0xFF).toByte()
                    byteArrayOf(marker, mid, low)
                }
                else -> throw IllegalArgumentException("Offset too large for encoding")
            }
        }

        /** Decode bytes to (offset, encoding_type) */
        fun decode(bytes: ByteArray): Pair<Long, OffsetEncoding>? {
            if (bytes.isEmpty()) return null

            val marker = bytes[0].toInt() and 0xFF
            val encodingType = when ((marker shr 6) and 0x03) {
                0 -> Short
                1 -> UShort
                2 -> ULong
                else -> Reserved
            }

            val offset = when (encodingType) {
                Short -> (marker and 0x3F).toLong()
                UShort -> {
                    if (bytes.size < 2) return null
                    val high = (marker and 0x3F).toLong()
                    val low = (bytes[1].toInt() and 0xFF).toLong()
                    (high shl 8) or low
                }
                ULong -> {
                    if (bytes.size < 3) return null
                    val high = (marker and 0x3F).toLong()
                    val mid = (bytes[1].toInt() and 0xFF).toLong()
                    val low = (bytes[2].toInt() and 0xFF).toLong()
                    (high shl 16) or (mid shl 8) or low
                }
                Reserved -> return null
            }

            return offset to encodingType
        }
    }
}

/** JsonIndex - Index structure for JSON */
class JsonIndex {
    companion object {
        /** Create new empty index */
        fun create(): JsonIndex = JsonIndex()
    }
}
