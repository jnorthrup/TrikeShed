package borg.trikeshed.context

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.enums.EnumEntries

/**
 * Interface for enums that represent bitmasks — behaves exactly like UInt.
 * All operators are inline infix for zero-overhead.
 */
interface BitMasked {
    /** The underlying bit mask (1 << ordinal) */
    val mask: UInt get() = 1u shl (this as Enum<*>).ordinal

    /** Ordinal-based comparisons */
    fun isAtLeast(other: BitMasked): Boolean = (this as Enum<*>).ordinal >= (other as Enum<*>).ordinal
    fun isAtMost(other: BitMasked): Boolean = (this as Enum<*>).ordinal <= (other as Enum<*>).ordinal
    fun isLessThan(other: BitMasked): Boolean = (this as Enum<*>).ordinal < (other as Enum<*>).ordinal
    fun isGreaterThan(other: BitMasked): Boolean = (this as Enum<*>).ordinal > (other as Enum<*>).ordinal

    companion object {
        /** Lazy ordinal index: Series<Int> mapping position to enum ordinal. */
        fun <E> ordinalIndex(entries: EnumEntries<E>): Lazy<Series<Int>> where E : Enum<E>, E : BitMasked =
            lazy { entries.size j { i: Int -> i } }

        /** Lazy name index: Pair of (sorted names Series, ordinals Series). */
        fun <E> nameIndex(entries: EnumEntries<E>): Lazy<Pair<Series<String>, Series<Int>>> where E : Enum<E>, E : BitMasked =
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

// ========== BEFORE: BitMasked op X ==========

// BitMasked >> BitMasked
inline infix fun <reified E> BitMasked.and(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask and other.mask
inline infix fun <reified E> BitMasked.or(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask or other.mask
inline infix fun <reified E> BitMasked.xor(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask xor other.mask
inline infix fun <reified E> BitMasked.plus(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask + other.mask
inline infix fun <reified E> BitMasked.minus(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask - other.mask
inline infix fun <reified E> BitMasked.times(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask * other.mask
inline infix fun <reified E> BitMasked.div(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask / other.mask
inline infix fun <reified E> BitMasked.rem(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask % other.mask
inline infix fun <reified E> BitMasked.shl(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask shl other.mask.toInt()
inline infix fun <reified E> BitMasked.shr(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask shr other.mask.toInt()

// BitMasked >> UInt/Int
inline infix fun BitMasked.and(mask: UInt): UInt = this.mask and mask
inline infix fun BitMasked.or(mask: UInt): UInt = this.mask or mask
inline infix fun BitMasked.xor(mask: UInt): UInt = this.mask xor mask
inline fun BitMasked.not(): UInt = this.mask.inv()
inline infix fun BitMasked.plus(mask: UInt): UInt = this.mask + mask
inline infix fun BitMasked.minus(mask: UInt): UInt = this.mask - mask
inline infix fun BitMasked.times(mask: UInt): UInt = this.mask * mask
inline infix fun BitMasked.div(mask: UInt): UInt = this.mask / mask
inline infix fun BitMasked.rem(mask: UInt): UInt = this.mask % mask
inline infix fun BitMasked.shl(bits: Int): UInt = this.mask shl bits
inline infix fun BitMasked.shr(bits: Int): UInt = this.mask shr bits
inline infix fun BitMasked.plus(n: Int): UInt = (this.mask.toInt() + n).toUInt()
inline infix fun BitMasked.minus(n: Int): UInt = (this.mask.toInt() - n).toUInt()
inline infix fun BitMasked.times(n: Int): UInt = (this.mask.toInt() * n).toUInt()
inline infix fun BitMasked.div(n: Int): UInt = (this.mask.toInt() / n).toUInt()
inline infix fun BitMasked.rem(n: Int): UInt = (this.mask.toInt() % n).toUInt()

// ========== AFTER: X >> BitMasked ==========

// UInt >> BitMasked
inline infix fun UInt.and(bm: BitMasked): UInt = this and bm.mask
inline infix fun UInt.or(bm: BitMasked): UInt = this or bm.mask
inline infix fun UInt.xor(bm: BitMasked): UInt = this xor bm.mask
inline infix fun UInt.plus(bm: BitMasked): UInt = this + bm.mask
inline infix fun UInt.minus(bm: BitMasked): UInt = this - bm.mask
inline infix fun UInt.times(bm: BitMasked): UInt = this * bm.mask
inline infix fun UInt.div(bm: BitMasked): UInt = this / bm.mask
inline infix fun UInt.rem(bm: BitMasked): UInt = this % bm.mask

// Int >> BitMasked  
inline infix fun Int.and(bm: BitMasked): UInt = (this and bm.mask.toInt()).toUInt()
inline infix fun Int.or(bm: BitMasked): UInt = (this or bm.mask.toInt()).toUInt()
inline infix fun Int.xor(bm: BitMasked): UInt = (this xor bm.mask.toInt()).toUInt()
inline infix fun Int.plus(bm: BitMasked): UInt = (this + bm.mask.toInt()).toUInt()
inline infix fun Int.minus(bm: BitMasked): UInt = (this - bm.mask.toInt()).toUInt()
inline infix fun Int.times(bm: BitMasked): UInt = (this * bm.mask.toInt()).toUInt()
inline infix fun Int.div(bm: BitMasked): UInt = (this / bm.mask.toInt()).toUInt()
inline infix fun Int.rem(bm: BitMasked): UInt = (this % bm.mask.toInt()).toUInt()

// ========== BEFORE: BitMasked comparisons ==========

// BitMasked >> BitMasked
inline infix fun <reified E> BitMasked.eq(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask == other.mask
inline infix fun <reified E> BitMasked.ne(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask != other.mask
inline infix fun <reified E> BitMasked.lt(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask < other.mask
inline infix fun <reified E> BitMasked.gt(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask > other.mask
inline infix fun <reified E> BitMasked.le(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask <= other.mask
inline infix fun <reified E> BitMasked.ge(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask >= other.mask

// BitMasked >> UInt
inline infix fun BitMasked.eq(mask: UInt): Boolean = this.mask == mask
inline infix fun BitMasked.ne(mask: UInt): Boolean = this.mask != mask
inline infix fun BitMasked.lt(mask: UInt): Boolean = this.mask < mask
inline infix fun BitMasked.gt(mask: UInt): Boolean = this.mask > mask
inline infix fun BitMasked.le(mask: UInt): Boolean = this.mask <= mask
inline infix fun BitMasked.ge(mask: UInt): Boolean = this.mask >= mask

// BitMasked >> Int
inline infix fun BitMasked.eq(n: Int): Boolean = this.mask.toInt() == n
inline infix fun BitMasked.ne(n: Int): Boolean = this.mask.toInt() != n
inline infix fun BitMasked.lt(n: Int): Boolean = this.mask.toInt() < n
inline infix fun BitMasked.gt(n: Int): Boolean = this.mask.toInt() > n
inline infix fun BitMasked.le(n: Int): Boolean = this.mask.toInt() <= n
inline infix fun BitMasked.ge(n: Int): Boolean = this.mask.toInt() >= n

// ========== AFTER: UInt/Int >> BitMasked comparisons ==========

inline infix fun UInt.eq(bm: BitMasked): Boolean = this == bm.mask
inline infix fun UInt.ne(bm: BitMasked): Boolean = this != bm.mask
inline infix fun UInt.lt(bm: BitMasked): Boolean = this < bm.mask
inline infix fun UInt.gt(bm: BitMasked): Boolean = this > bm.mask
inline infix fun UInt.le(bm: BitMasked): Boolean = this <= bm.mask
inline infix fun UInt.ge(bm: BitMasked): Boolean = this >= bm.mask

inline infix fun Int.eq(bm: BitMasked): Boolean = this == bm.mask.toInt()
inline infix fun Int.ne(bm: BitMasked): Boolean = this != bm.mask.toInt()
inline infix fun Int.lt(bm: BitMasked): Boolean = this < bm.mask.toInt()
inline infix fun Int.gt(bm: BitMasked): Boolean = this > bm.mask.toInt()
inline infix fun Int.le(bm: BitMasked): Boolean = this <= bm.mask.toInt()
inline infix fun Int.ge(bm: BitMasked): Boolean = this >= bm.mask.toInt()

// ========== Ranges: BEFORE and AFTER ==========

// BitMasked >> BitMasked
inline infix fun <reified E> BitMasked.until(other: E): UIntRange where E : Enum<E>, E : BitMasked = this.mask until other.mask
inline infix fun <reified E> BitMasked.rangeTo(other: E): UIntRange where E : Enum<E>, E : BitMasked = this.mask..other.mask

// BitMasked >> UInt
inline infix fun BitMasked.until(mask: UInt): UIntRange = this.mask until mask
inline infix fun BitMasked.rangeTo(mask: UInt): UIntRange = this.mask..mask

// BitMasked >> Int
inline infix fun BitMasked.until(n: Int): IntRange = this.mask.toInt() until n
inline infix fun BitMasked.rangeTo(n: Int): IntRange = this.mask.toInt()..n

// UInt >> BitMasked
inline infix fun UInt.until(bm: BitMasked): UIntRange = this until bm.mask
inline infix fun UInt.rangeTo(bm: BitMasked): UIntRange = this..bm.mask

// Int >> BitMasked
inline infix fun Int.until(bm: BitMasked): IntRange = this until bm.mask.toInt()
inline infix fun Int.rangeTo(bm: BitMasked): IntRange = this..bm.mask.toInt()

// ========== Bitwise helpers ==========

inline fun BitMasked.getBit(bit: Int): Boolean = (this.mask and (1u shl bit)) != 0u
inline fun BitMasked.setBit(bit: Int): UInt = this.mask or (1u shl bit)
inline fun BitMasked.clearBit(bit: Int): UInt = this.mask and (1u shl bit).inv()
inline fun BitMasked.toggleBit(bit: Int): UInt = this.mask xor (1u shl bit)
inline fun BitMasked.rotateLeft(bits: Int): UInt = (this.mask shl bits) or (this.mask shr (32 - bits))
inline fun BitMasked.rotateRight(bits: Int): UInt = (this.mask shr bits) or (this.mask shl (32 - bits))

// ========== Boolean logic (non-bitwise) ==========

inline infix fun <reified E> BitMasked.andAlso(other: E): Boolean where E : Enum<E>, E : BitMasked = (this.mask and other.mask) != 0u
inline infix fun <reified E> BitMasked.orElse(other: E): Boolean where E : Enum<E>, E : BitMasked = (this.mask or other.mask) != 0u
inline infix fun <reified E> BitMasked.xorElse(other: E): Boolean where E : Enum<E>, E : BitMasked = ((this.mask or other.mask) - (this.mask and other.mask)) != 0u
inline infix fun BitMasked.andAlso(mask: UInt): Boolean = (this.mask and mask) != 0u
inline infix fun BitMasked.orElse(mask: UInt): Boolean = (this.mask or mask) != 0u
inline fun BitMasked.logicalNot(): Boolean = this.mask == 0u

// ========== Conversion ==========

inline fun BitMasked.toUInt(): UInt = this.mask
inline fun BitMasked.toInt(): Int = this.mask.toInt()
inline fun BitMasked.toLong(): Long = this.mask.toLong()