package borg.trikeshed.context

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.enums.EnumEntries
import kotlin.jvm.JvmName

/**
 * Interface for enums that represent bitmasks over primitive type P.
 * P is constrained to Comparable — covers UInt, ULong, UShort, UByte, Int, Long, Short, Byte.
 * Bitwise operators are provided as per-concrete-P extension functions below.
 * Implementing enums must override [mask].
 */
interface BitMasked<P : Comparable<P>> {
    /** The underlying bit mask. Implementors provide this (e.g. `1u shl ordinal`). */
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

// ========== UInt operators: BitMasked<UInt> ==========

// BitMasked<UInt> >> BitMasked<UInt>
inline infix fun <reified E> BitMasked<UInt>.and(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask and other.mask
inline infix fun <reified E> BitMasked<UInt>.or(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask or other.mask
inline infix fun <reified E> BitMasked<UInt>.xor(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask xor other.mask
inline infix fun <reified E> BitMasked<UInt>.plus(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask + other.mask
inline infix fun <reified E> BitMasked<UInt>.minus(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask - other.mask
inline infix fun <reified E> BitMasked<UInt>.times(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask * other.mask
inline infix fun <reified E> BitMasked<UInt>.div(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask / other.mask
inline infix fun <reified E> BitMasked<UInt>.rem(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask % other.mask
inline infix fun <reified E> BitMasked<UInt>.shl(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask shl other.mask.toInt()
inline infix fun <reified E> BitMasked<UInt>.shr(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask shr other.mask.toInt()

// BitMasked<UInt> >> UInt/Int
infix fun BitMasked<UInt>.and(mask: UInt): UInt = this.mask and mask
infix fun BitMasked<UInt>.or(mask: UInt): UInt = this.mask or mask
infix fun BitMasked<UInt>.xor(mask: UInt): UInt = this.mask xor mask
fun BitMasked<UInt>.not(): UInt = this.mask.inv()
infix fun BitMasked<UInt>.plus(mask: UInt): UInt = this.mask + mask
infix fun BitMasked<UInt>.minus(mask: UInt): UInt = this.mask - mask
infix fun BitMasked<UInt>.times(mask: UInt): UInt = this.mask * mask
infix fun BitMasked<UInt>.div(mask: UInt): UInt = this.mask / mask
infix fun BitMasked<UInt>.rem(mask: UInt): UInt = this.mask % mask
infix fun BitMasked<UInt>.shl(bits: Int): UInt = this.mask shl bits
infix fun BitMasked<UInt>.shr(bits: Int): UInt = this.mask shr bits
infix fun BitMasked<UInt>.plus(n: Int): UInt = (this.mask.toInt() + n).toUInt()
infix fun BitMasked<UInt>.minus(n: Int): UInt = (this.mask.toInt() - n).toUInt()
infix fun BitMasked<UInt>.times(n: Int): UInt = (this.mask.toInt() * n).toUInt()
infix fun BitMasked<UInt>.div(n: Int): UInt = (this.mask.toInt() / n).toUInt()
infix fun BitMasked<UInt>.rem(n: Int): UInt = (this.mask.toInt() % n).toUInt()

// UInt >> BitMasked<UInt>
infix fun UInt.and(bm: BitMasked<UInt>): UInt = this and bm.mask
infix fun UInt.or(bm: BitMasked<UInt>): UInt = this or bm.mask
infix fun UInt.xor(bm: BitMasked<UInt>): UInt = this xor bm.mask
infix fun UInt.plus(bm: BitMasked<UInt>): UInt = this + bm.mask
infix fun UInt.minus(bm: BitMasked<UInt>): UInt = this - bm.mask
infix fun UInt.times(bm: BitMasked<UInt>): UInt = this * bm.mask
infix fun UInt.div(bm: BitMasked<UInt>): UInt = this / bm.mask
infix fun UInt.rem(bm: BitMasked<UInt>): UInt = this % bm.mask

// Int >> BitMasked<UInt>
infix fun Int.and(bm: BitMasked<UInt>): UInt = (this and bm.mask.toInt()).toUInt()
infix fun Int.or(bm: BitMasked<UInt>): UInt = (this or bm.mask.toInt()).toUInt()
infix fun Int.xor(bm: BitMasked<UInt>): UInt = (this xor bm.mask.toInt()).toUInt()
infix fun Int.plus(bm: BitMasked<UInt>): UInt = (this + bm.mask.toInt()).toUInt()
infix fun Int.minus(bm: BitMasked<UInt>): UInt = (this - bm.mask.toInt()).toUInt()
infix fun Int.times(bm: BitMasked<UInt>): UInt = (this * bm.mask.toInt()).toUInt()
infix fun Int.div(bm: BitMasked<UInt>): UInt = (this / bm.mask.toInt()).toUInt()
infix fun Int.rem(bm: BitMasked<UInt>): UInt = (this % bm.mask.toInt()).toUInt()

// BitMasked<UInt> comparisons >> UInt
infix fun BitMasked<UInt>.eq(mask: UInt): Boolean = this.mask == mask
infix fun BitMasked<UInt>.ne(mask: UInt): Boolean = this.mask != mask
infix fun BitMasked<UInt>.lt(mask: UInt): Boolean = this.mask < mask
infix fun BitMasked<UInt>.gt(mask: UInt): Boolean = this.mask > mask
infix fun BitMasked<UInt>.le(mask: UInt): Boolean = this.mask <= mask
infix fun BitMasked<UInt>.ge(mask: UInt): Boolean = this.mask >= mask

// BitMasked<UInt> comparisons >> Int
infix fun BitMasked<UInt>.eq(n: Int): Boolean = this.mask.toInt() == n
infix fun BitMasked<UInt>.ne(n: Int): Boolean = this.mask.toInt() != n
infix fun BitMasked<UInt>.lt(n: Int): Boolean = this.mask.toInt() < n
infix fun BitMasked<UInt>.gt(n: Int): Boolean = this.mask.toInt() > n
infix fun BitMasked<UInt>.le(n: Int): Boolean = this.mask.toInt() <= n
infix fun BitMasked<UInt>.ge(n: Int): Boolean = this.mask.toInt() >= n

// UInt/Int >> BitMasked<UInt> comparisons
infix fun UInt.eq(bm: BitMasked<UInt>): Boolean = this == bm.mask
infix fun UInt.ne(bm: BitMasked<UInt>): Boolean = this != bm.mask
infix fun UInt.lt(bm: BitMasked<UInt>): Boolean = this < bm.mask
infix fun UInt.gt(bm: BitMasked<UInt>): Boolean = this > bm.mask
infix fun UInt.le(bm: BitMasked<UInt>): Boolean = this <= bm.mask
infix fun UInt.ge(bm: BitMasked<UInt>): Boolean = this >= bm.mask

infix fun Int.eq(bm: BitMasked<UInt>): Boolean = this == bm.mask.toInt()
infix fun Int.ne(bm: BitMasked<UInt>): Boolean = this != bm.mask.toInt()
infix fun Int.lt(bm: BitMasked<UInt>): Boolean = this < bm.mask.toInt()
infix fun Int.gt(bm: BitMasked<UInt>): Boolean = this > bm.mask.toInt()
infix fun Int.le(bm: BitMasked<UInt>): Boolean = this <= bm.mask.toInt()
infix fun Int.ge(bm: BitMasked<UInt>): Boolean = this >= bm.mask.toInt()

// BitMasked<UInt> ranges
infix fun BitMasked<UInt>.until(mask: UInt): UIntRange = this.mask until mask
infix fun BitMasked<UInt>.rangeTo(mask: UInt): UIntRange = this.mask..mask
infix fun BitMasked<UInt>.until(n: Int): IntRange = this.mask.toInt() until n
infix fun BitMasked<UInt>.rangeTo(n: Int): IntRange = this.mask.toInt()..n
infix fun UInt.until(bm: BitMasked<UInt>): UIntRange = this until bm.mask
infix fun UInt.rangeTo(bm: BitMasked<UInt>): UIntRange = this..bm.mask
infix fun Int.until(bm: BitMasked<UInt>): IntRange = this until bm.mask.toInt()
infix fun Int.rangeTo(bm: BitMasked<UInt>): IntRange = this..bm.mask.toInt()

// BitMasked<UInt> bitwise helpers
fun BitMasked<UInt>.getBit(bit: Int): Boolean = (this.mask and (1u shl bit)) != 0u
fun BitMasked<UInt>.setBit(bit: Int): UInt = this.mask or (1u shl bit)
fun BitMasked<UInt>.clearBit(bit: Int): UInt = this.mask and (1u shl bit).inv()
fun BitMasked<UInt>.toggleBit(bit: Int): UInt = this.mask xor (1u shl bit)
fun BitMasked<UInt>.rotateLeft(bits: Int): UInt = (this.mask shl bits) or (this.mask shr (32 - bits))
fun BitMasked<UInt>.rotateRight(bits: Int): UInt = (this.mask shr bits) or (this.mask shl (32 - bits))

// BitMasked<UInt> boolean logic
infix fun BitMasked<UInt>.andAlso(mask: UInt): Boolean = (this.mask and mask) != 0u
infix fun BitMasked<UInt>.orElse(mask: UInt): Boolean = (this.mask or mask) != 0u
@JvmName("logicalNotUInt")
fun BitMasked<UInt>.logicalNot(): Boolean = this.mask == 0u

// BitMasked<UInt> conversion
fun BitMasked<UInt>.toUInt(): UInt = this.mask
@JvmName("toIntUInt")
fun BitMasked<UInt>.toInt(): Int = this.mask.toInt()
@JvmName("toLongUInt")
fun BitMasked<UInt>.toLong(): Long = this.mask.toLong()

// ========== Long operators: BitMasked<Long> ==========

// BitMasked<Long> >> BitMasked<Long>
inline infix fun <reified E> BitMasked<Long>.and(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask and other.mask
inline infix fun <reified E> BitMasked<Long>.or(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask or other.mask
inline infix fun <reified E> BitMasked<Long>.xor(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask xor other.mask
inline infix fun <reified E> BitMasked<Long>.plus(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask + other.mask
inline infix fun <reified E> BitMasked<Long>.minus(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask - other.mask
inline infix fun <reified E> BitMasked<Long>.shl(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask shl other.mask.toInt()
inline infix fun <reified E> BitMasked<Long>.shr(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask shr other.mask.toInt()

// BitMasked<Long> >> Long
infix fun BitMasked<Long>.and(mask: Long): Long = this.mask and mask
infix fun BitMasked<Long>.or(mask: Long): Long = this.mask or mask
infix fun BitMasked<Long>.xor(mask: Long): Long = this.mask xor mask
fun BitMasked<Long>.not(): Long = this.mask.inv()
infix fun BitMasked<Long>.plus(mask: Long): Long = this.mask + mask
infix fun BitMasked<Long>.minus(mask: Long): Long = this.mask - mask
infix fun BitMasked<Long>.shl(bits: Int): Long = this.mask shl bits
infix fun BitMasked<Long>.shr(bits: Int): Long = this.mask shr bits

// Long >> BitMasked<Long>
infix fun Long.and(bm: BitMasked<Long>): Long = this and bm.mask
infix fun Long.or(bm: BitMasked<Long>): Long = this or bm.mask
infix fun Long.xor(bm: BitMasked<Long>): Long = this xor bm.mask
infix fun Long.plus(bm: BitMasked<Long>): Long = this + bm.mask
infix fun Long.minus(bm: BitMasked<Long>): Long = this - bm.mask

// BitMasked<Long> comparisons >> Long
infix fun BitMasked<Long>.eq(mask: Long): Boolean = this.mask == mask
infix fun BitMasked<Long>.ne(mask: Long): Boolean = this.mask != mask
infix fun BitMasked<Long>.lt(mask: Long): Boolean = this.mask < mask
infix fun BitMasked<Long>.gt(mask: Long): Boolean = this.mask > mask
infix fun BitMasked<Long>.le(mask: Long): Boolean = this.mask <= mask
infix fun BitMasked<Long>.ge(mask: Long): Boolean = this.mask >= mask

// BitMasked<Long> boolean logic
infix fun BitMasked<Long>.andAlso(mask: Long): Boolean = (this.mask and mask) != 0L
infix fun BitMasked<Long>.orElse(mask: Long): Boolean = (this.mask or mask) != 0L
@JvmName("logicalNotLong")
fun BitMasked<Long>.logicalNot(): Boolean = this.mask == 0L

// BitMasked<Long> conversion
@JvmName("toLongLong")
fun BitMasked<Long>.toLong(): Long = this.mask
@JvmName("toIntLong")
fun BitMasked<Long>.toInt(): Int = this.mask.toInt()
fun BitMasked<Long>.toULong(): ULong = this.mask.toULong()

// ========== Back-compat typealias ==========

/** Legacy alias: BitMaskedLong is now BitMasked<Long>. */
typealias BitMaskedLong = BitMasked<Long>
