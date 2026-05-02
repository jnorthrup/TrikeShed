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

// ========== BitMasked >> BitMasked ops (return UInt) ==========

inline infix fun <reified E> BitMasked.and(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask and other.mask
inline infix fun <reified E> BitMasked.or(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask or other.mask
inline infix fun <reified E> BitMasked.xor(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask xor other.mask
inline fun <reified E> BitMasked.not(): UInt where E : Enum<E>, E : BitMasked = this.mask.inv()

inline infix fun <reified E> BitMasked.plus(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask + other.mask
inline infix fun <reified E> BitMasked.minus(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask - other.mask
inline infix fun <reified E> BitMasked.times(other: E): UInt where E : Enum<E>, E : BitMasked = this.mask * other.mask

inline infix fun <reified E> BitMasked.shiftLeft(bits: Int): UInt where E : Enum<E>, E : BitMasked = this.mask shl bits
inline infix fun <reified E> BitMasked.shiftRight(bits: Int): UInt where E : Enum<E>, E : BitMasked = this.mask shr bits

// ========== BitMasked >> primitive UInt ops ==========

inline infix fun BitMasked.and(mask: UInt): UInt = this.mask and mask
inline infix fun BitMasked.or(mask: UInt): UInt = this.mask or mask
inline infix fun BitMasked.xor(mask: UInt): UInt = this.mask xor mask
inline fun BitMasked.not(): UInt = this.mask.inv()

inline infix fun BitMasked.plus(mask: UInt): UInt = this.mask + mask
inline infix fun BitMasked.minus(mask: UInt): UInt = this.mask - mask
inline infix fun BitMasked.times(mask: UInt): UInt = this.mask * mask
inline infix fun BitMasked.div(mask: UInt): UInt = this.mask / mask
inline infix fun BitMasked.rem(mask: UInt): UInt = this.mask % mask

inline infix fun BitMasked.shiftLeft(bits: Int): UInt = this.mask shl bits
inline infix fun BitMasked.shiftRight(bits: Int): UInt = this.mask shr bits

// ========== Primitive UInt >> BitMasked ops (reversed) ==========

inline infix fun UInt.and(bm: BitMasked): UInt = this and bm.mask
inline infix fun UInt.or(bm: BitMasked): UInt = this or bm.mask
inline infix fun UInt.xor(bm: BitMasked): UInt = this xor bm.mask

inline infix fun UInt.plus(bm: BitMasked): UInt = this + bm.mask
inline infix fun UInt.minus(bm: BitMasked): UInt = this - bm.mask
inline infix fun UInt.times(bm: BitMasked): UInt = this * bm.mask
inline infix fun UInt.div(bm: BitMasked): UInt = this / bm.mask
inline infix fun UInt.rem(bm: BitMasked): UInt = this % bm.mask

// ========== BitMasked comparisons (return Boolean) ==========

inline infix fun <reified E> BitMasked.eq(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask == other.mask
inline infix fun <reified E> BitMasked.ne(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask != other.mask
inline infix fun <reified E> BitMasked.lt(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask < other.mask
inline infix fun <reified E> BitMasked.gt(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask > other.mask
inline infix fun <reified E> BitMasked.le(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask <= other.mask
inline infix fun <reified E> BitMasked.ge(other: E): Boolean where E : Enum<E>, E : BitMasked = this.mask >= other.mask

inline infix fun BitMasked.eq(mask: UInt): Boolean = this.mask == mask
inline infix fun BitMasked.ne(mask: UInt): Boolean = this.mask != mask
inline infix fun BitMasked.lt(mask: UInt): Boolean = this.mask < mask
inline infix fun BitMasked.gt(mask: UInt): Boolean = this.mask > mask
inline infix fun BitMasked.le(mask: UInt): Boolean = this.mask <= mask
inline infix fun BitMasked.ge(mask: UInt): Boolean = this.mask >= mask

inline infix fun UInt.eq(bm: BitMasked): Boolean = this == bm.mask
inline infix fun UInt.ne(bm: BitMasked): Boolean = this != bm.mask
inline infix fun UInt.lt(bm: BitMasked): Boolean = this < bm.mask
inline infix fun UInt.gt(bm: BitMasked): Boolean = this > bm.mask
inline infix fun UInt.le(bm: BitMasked): Boolean = this <= bm.mask
inline infix fun UInt.ge(bm: BitMasked): Boolean = this >= bm.mask

// ========== BitMasked to primitive conversion ==========

inline fun BitMasked.toUInt(): UInt = this.mask
inline fun BitMasked.toInt(): Int = this.mask.toInt()
inline fun BitMasked.toLong(): Long = this.mask.toLong()

// ========== Bitwise helpers on BitMasked ==========

inline fun BitMasked.getBit(bit: Int): Boolean = (this.mask and (1u shl bit)) != 0u
inline fun BitMasked.setBit(bit: Int): UInt = this.mask or (1u shl bit)
inline fun BitMasked.clearBit(bit: Int): UInt = this.mask and (1u shl bit).inv()
inline fun BitMasked.toggleBit(bit: Int): UInt = this.mask xor (1u shl bit)

// Rotate left/right
inline fun BitMasked.rotateLeft(bits: Int): UInt {
    val shifted = this.mask shl bits
    val wrapped = this.mask shr (32 - bits)
    return shifted or wrapped
}
inline fun BitMasked.rotateRight(bits: Int): UInt {
    val shifted = this.mask shr bits
    val wrapped = this.mask shl (32 - bits)
    return shifted or wrapped
}

// ========== Range operators ==========

inline infix fun <reified E> BitMasked.until(other: E): UIntRange where E : Enum<E>, E : BitMasked = this.mask until other.mask
inline infix fun <reified E> BitMasked.rangeTo(other: E): UIntRange where E : Enum<E>, E : BitMasked = this.mask..other.mask
inline infix fun BitMasked.until(mask: UInt): UIntRange = this.mask until mask
inline infix fun BitMasked.rangeTo(mask: UInt): UIntRange = this.mask..mask
inline infix fun UInt.until(bm: BitMasked): UIntRange = this until bm.mask
inline infix fun UInt.rangeTo(bm: BitMasked): UIntRange = this..bm.mask

// ========== Boolean logic (non-bitwise) ==========

inline infix fun <reified E> BitMasked.andAlso(other: E): Boolean where E : Enum<E>, E : BitMasked = (this.mask and other.mask) != 0u
inline infix fun <reified E> BitMasked.orElse(other: E): Boolean where E : Enum<E>, E : BitMasked = (this.mask or other.mask) != 0u
inline infix fun <reified E> BitMasked.xorElse(other: E): Boolean where E : Enum<E>, E : BitMasked = ((this.mask or other.mask) - (this.mask and other.mask)) != 0u

inline infix fun BitMasked.andAlso(mask: UInt): Boolean = (this.mask and mask) != 0u
inline infix fun BitMasked.orElse(mask: UInt): Boolean = (this.mask or mask) != 0u
inline fun BitMasked.logicalNot(): Boolean = this.mask == 0u