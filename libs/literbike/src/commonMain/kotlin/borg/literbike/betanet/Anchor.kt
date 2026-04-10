package borg.literbike.betanet

/**
 * Anchor - testable anchor matching and priority resolution.
 * Ported from literbike/src/betanet/anchor.rs.
 */

data class Anchor(
    val pattern: Long,
    val priority: Int,
    val mask: Long = 0L // lower 64 bits of the u128 mask
) {
    /** Returns true if the anchor matches anywhere in data */
    fun matches(data: ByteArray): Boolean = firstMatchOffset(data) != null

    /** Returns the offset (byte index) of the first 8-byte window that matches */
    fun firstMatchOffset(data: ByteArray): Int? {
        if (data.size < 8) return null

        val maskU64 = if (mask == 0L) -1L else (mask and 0xFFFF_FFFF_FFFF_FFFFL)
        val pat = pattern and maskU64

        for (i in 0 until data.size - 7) {
            val word = ((data[i].toLong() and 0xFF) shl 56) or
                    ((data[i + 1].toLong() and 0xFF) shl 48) or
                    ((data[i + 2].toLong() and 0xFF) shl 40) or
                    ((data[i + 3].toLong() and 0xFF) shl 32) or
                    ((data[i + 4].toLong() and 0xFF) shl 24) or
                    ((data[i + 5].toLong() and 0xFF) shl 16) or
                    ((data[i + 6].toLong() and 0xFF) shl 8) or
                    (data[i + 7].toLong() and 0xFF)
            if ((word and maskU64) == pat) {
                return i
            }
        }
        return null
    }
}

/**
 * Protocol detector composed from an Anchor set.
 */
class ProtocolDetector(private val anchors: List<Anchor>) {

    /** Returns the highest-priority matching anchor, if any */
    fun detect(data: ByteArray): Anchor? {
        var best: Pair<Anchor, Int>? = null // (anchor, offset)

        for (a in anchors) {
            a.firstMatchOffset(data)?.let { off ->
                if (best == null) {
                    best = a to off
                } else {
                    val (existing, exOff) = best!!
                    if (a.priority > existing.priority) {
                        best = a to off
                    } else if (a.priority == existing.priority && off < exOff) {
                        best = a to off
                    }
                }
            }
        }

        return best?.first
    }
}
