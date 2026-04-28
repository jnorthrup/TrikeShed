@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.patl

import borg.trikeshed.lib.Series

typealias word_t = UInt

/**
 * Byte-level bit comparator for patricia tries.
 * Compares two keys (extracted to byte sequences) and returns the bit position
 * of the first mismatch, or [ALL_MATCH] for identical keys (up to the shorter key's length).
 */
class BitComp<K>(val extract: (K) -> Series<Byte>) {

    companion object {
        /** Sentinel: all bits matched (keys are identical). */
        val ALL_MATCH: word_t = word_t.MAX_VALUE
    }

    /**
     * Returns the bit position of the first mismatch between [a] and [b],
     * or [ALL_MATCH] if the keys are identical.
     *
     * Bit positions are numbered from 0, counting LSB-first within each byte.
     * The overall bit position = byteIndex * 8 + bitWithinByte.
     */
    fun mismatch(a: K, b: K): word_t {
        val sa = extract(a)
        val sb = extract(b)
        val lenA = sa.a
        val lenB = sb.a
        val minLen = minOf(lenA, lenB)
        var i = 0
        while (i < minLen) {
            val ba = sa.b(i).toUInt()
            val bb = sb.b(i).toUInt()
            if (ba != bb) {
                val xor = ba xor bb
                return (i * 8).toUInt() + xor.countTrailingZeroBits().toUInt()
            }
            i++
        }
        // All bytes matched up to the shorter length.
        // If lengths are equal, the keys are identical.
        return if (lenA == lenB) ALL_MATCH else (minLen * 8).toUInt()
    }
}
