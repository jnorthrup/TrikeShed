package borg.trikeshed.context

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.enums.EnumEntries

/**
 * Interface for enums that represent bitmasks.
 */
interface BitMasked {
    val mask: UInt get() = 1u shl (this as Enum<*>).ordinal

    fun isAtLeast(other: BitMasked): Boolean = (this as Enum<*>).ordinal >= (other as Enum<*>).ordinal
    fun isAtMost(other: BitMasked): Boolean = (this as Enum<*>).ordinal <= (other as Enum<*>).ordinal
    fun isLessThan(other: BitMasked): Boolean = (this as Enum<*>).ordinal < (other as Enum<*>).ordinal
    fun isGreaterThan(other: BitMasked): Boolean = (this as Enum<*>).ordinal > (other as Enum<*>).ordinal

    companion object {
        /**
         * Lazy ordinal index: Series<Int> mapping position to enum ordinal.
         * Call once per enum type to cache.
         */
        fun <E> ordinalIndex(entries: EnumEntries<E>): Lazy<Series<Int>> where E : Enum<E>, E : BitMasked =
            lazy {
                val size = entries.size
                val ords = IntArray(size) { i -> i }
                size j { i: Int -> ords[i] }
            }

        /**
         * Lazy name index: Pair of (sorted names Series, ordinals Series).
         * Call once per enum type to cache, then use findOrdinal for lookup.
         */
        fun <E> nameIndex(entries: EnumEntries<E>): Lazy<Pair<Series<String>, Series<Int>>> where E : Enum<E>, E : BitMasked =
            lazy {
                val sorted = entries.sortedBy { it.name }
                val size = sorted.size
                val names = Array(size) { i -> sorted[i].name }
                val ords = IntArray(size) { i -> sorted[i].ordinal }

                (size j { i: Int -> names[i] }) to (size j { i: Int -> ords[i] })
            }

        /**
         * Binary search for a name in the sorted name index.
         * Returns the ordinal, or -1 if not found.
         */
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

/**
 * Bitmasked enum algebra: inline infix operators for bitwise AND/OR/XOR/NOT.
 */
inline infix fun <reified E> BitMasked.and(other: E): UInt where E : Enum<E>, E : BitMasked =
    this.mask and other.mask

inline infix fun <reified E> BitMasked.or(other: E): UInt where E : Enum<E>, E : BitMasked =
    this.mask or other.mask

inline infix fun <reified E> BitMasked.xor(other: E): UInt where E : Enum<E>, E : BitMasked =
    this.mask xor other.mask

inline fun <reified E> BitMasked.not(): UInt where E : Enum<E>, E : BitMasked =
    this.mask.inv()

/**
 * Bitmasked vs primitive UInt ops.
 */
inline infix fun BitMasked.and(mask: UInt): UInt = this.mask and mask
inline infix fun BitMasked.or(mask: UInt): UInt = this.mask or mask
inline infix fun BitMasked.xor(mask: UInt): UInt = this.mask xor mask

/**
 * Primitive UInt ops vs Bitmasked (reversed).
 */
inline infix fun UInt.andBm(bm: BitMasked): UInt = this and bm.mask
inline infix fun UInt.orBm(bm: BitMasked): UInt = this or bm.mask
inline infix fun UInt.xorBm(bm: BitMasked): UInt = this xor bm.mask

/**
 * Bitmasked arithmetic with primitives: add/inc/dec etc.
 * inc/dec return the incremented/decremented mask UInt.
 */
inline infix fun BitMasked.add(value: Int): UInt = (this.mask.toInt() + value).toUInt()
inline infix fun BitMasked.add(value: UInt): UInt = this.mask + value
inline fun BitMasked.inc(): UInt = (this.mask.toInt() + 1).toUInt()
inline fun BitMasked.dec(): UInt = (this.mask.toInt() - 1).toUInt()
inline infix fun BitMasked.sub(value: Int): UInt = (this.mask.toInt() - value).toUInt()
inline infix fun BitMasked.sub(value: UInt): UInt = this.mask - value
inline infix fun BitMasked.mul(value: Int): UInt = (this.mask.toInt() * value).toUInt()
inline infix fun BitMasked.mul(value: UInt): UInt = this.mask * value
inline infix fun BitMasked.div(value: Int): UInt = (this.mask.toInt() / value).toUInt()
inline infix fun BitMasked.div(value: UInt): UInt = this.mask / value
inline infix fun BitMasked.rem(value: Int): UInt = (this.mask.toInt() % value).toUInt()
inline infix fun BitMasked.rem(value: UInt): UInt = this.mask % value

/**
 * Reversed arithmetic: primitive + Bitmasked.
 */
inline operator infix fun Int.add(bm: BitMasked): UInt = (this + bm.mask.toInt()).toUInt()
inline operator infix fun UInt.add(bm: BitMasked): UInt = this + bm.mask
inline operator infix fun Int.sub(bm: BitMasked): UInt = (this - bm.mask.toInt()).toUInt()
inline operator infix fun UInt.sub(bm: BitMasked): UInt = this - bm.mask
inline operator infix fun Int.mul(bm: BitMasked): UInt = (this * bm.mask.toInt()).toUInt()
inline operator infix fun UInt.mul(bm: BitMasked): UInt = this * bm.mask
