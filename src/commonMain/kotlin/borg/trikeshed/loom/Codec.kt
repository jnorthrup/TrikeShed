package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*

/** cocaine-rats crates/loom-mesh/src/codec.rs: a narrow preflight in front of Confix's recursive decoder. */
const val MAX_WIRE: Int = 1_100_000
const val MAX_ITEMS: Int = 4096
const val MAX_DEPTH: Int = 16

/**
 * Wire CBOR is unsigned integers, byte strings, text up to 256 bytes and arrays, at most [MAX_ITEMS]
 * items nested at most [MAX_DEPTH] deep in at most [MAX_WIRE] bytes, with nothing trailing, and it
 * must re-encode byte for byte.
 */
fun decode(bytes: ByteArray): Item {
    if (bytes.isEmpty() || bytes.size > MAX_WIRE) error("CBOR size")
    var position = 0
    var budget = MAX_ITEMS
    fun preflight(depth: Int) {
        if (depth > MAX_DEPTH || budget == 0) error("CBOR complexity")
        budget--
        if (position == bytes.size) error("CBOR truncated")
        val head = bytes[position++].toInt() and 0xFF
        val major = head ushr 5
        if (major != 0 && major != 2 && major != 3 && major != 4) error("CBOR type")
        val additional = head and 31
        val value = when (additional) {
            in 0..23 -> additional.toLong()
            in 24..27 -> {
                val count = 1 shl (additional - 24)
                if (count > bytes.size - position) error("CBOR truncated")
                var value = 0L
                repeat(count) { value = (value shl 8) or (bytes[position++].toLong() and 0xFF) }
                value
            }
            else -> error("CBOR indefinite or reserved")
        }
        // a u64 above i64::MAX reads negative
        if (value < 0) error("CBOR integer range")
        when (major) {
            2, 3 -> {
                if (value > MAX_WIRE || (major == 3 && value > 256)) error("CBOR string size")
                val end = position + value.toInt()
                if (end > bytes.size) error("CBOR truncated")
                if (major == 3) try {
                    bytes.decodeToString(position, end, throwOnInvalidSequence = true)
                } catch (e: CharacterCodingException) {
                    error("CBOR UTF8")
                }
                position = end
            }
            4 -> {
                if (value > budget) error("CBOR array size")
                repeat(value.toInt()) { preflight(depth + 1) }
            }
        }
    }
    preflight(0)
    if (position != bytes.size) error("CBOR trailing bytes")
    val item = try {
        Cbor.decode(bytes)
    } catch (e: Exception) {
        error("CBOR decode")
    }
    if (!Cbor.encode(item).contentEquals(bytes)) error("CBOR noncanonical")
    return item
}
