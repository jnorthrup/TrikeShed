package borg.trikeshed.lib

import kotlin.enums.EnumEntries

/**
 * Interface for enums that represent bitmasks over primitive type P.
 * P is constrained to Comparable — covers UInt, ULong, UShort, UByte, Int, Long, Short, Byte.
 *
 * Implementing enums override [mask] — typically `1u shl ordinal` for UInt,
 * `1 shl ordinal` for Int, `(1 shl ordinal).toShort()` for Short.
 *
 * Operators are in sibling files:
 *   BitMaskedOpsUInt.kt   — BitMasked<UInt> operators
 *   BitMaskedOpsShort.kt  — BitMasked<Short> operators
 */
interface BitMasked<P : Comparable<P>> {
    /** The underlying bit mask. */
    val mask: P

    /** Ordinal-based comparisons via P's Comparable. */
    fun isAtLeast(other: BitMasked<P>): Boolean = this.mask >= other.mask
    fun isAtMost(other: BitMasked<P>): Boolean = this.mask <= other.mask
    fun isLessThan(other: BitMasked<P>): Boolean = this.mask < other.mask
    fun isGreaterThan(other: BitMasked<P>): Boolean = this.mask > other.mask

    companion object {
        /** Lazy ordinal index: Series<Int> mapping position to enum ordinal. */
        fun <E> ordinalIndex(entries: EnumEntries<E>): Lazy<Series<Int>> where E : Enum<E>, E : BitMasked<*> =
            lazy { entries.size j { i: Int -> i } }

        /** Lazy name index: Pair of (sorted names Series, ordinals Series). */
        fun <E> nameIndex(entries: EnumEntries<E>): Lazy<Pair<Series<String>, Series<Int>>> where E : Enum<E>, E : BitMasked<*> =
            lazy {
                val sorted = entries.sortedBy { it.name }
                val n = sorted.size
                (n j { i: Int -> sorted[i].name }) to (n j { i: Int -> sorted[i].ordinal })
            }

        /** Binary search for name. Returns ordinal or -1. */
        fun findOrdinal(nameIndex: Series<String>, ordinals: Series<Int>, name: String): Int {
            var lo = 0
            var hi = nameIndex.a - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val cmp = nameIndex.b(mid).compareTo(name)
                when {
                    cmp == 0 -> return ordinals.b(mid)
                    cmp < 0 -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            return -1
        }
    }
}
