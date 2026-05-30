@file:JvmName("BitMaskedOpsLong")
package borg.trikeshed.lib.long

import borg.trikeshed.lib.BitMasked

// BitMasked<Long> >> BitMasked<Long>
inline infix fun <reified E> BitMasked<Long>.and(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask and other.mask
inline infix fun <reified E> BitMasked<Long>.or(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask or other.mask
inline infix fun <reified E> BitMasked<Long>.xor(other: E): Long where E : Enum<E>, E : BitMasked<Long> = this.mask xor other.mask

// BitMasked<Long> >> Long
infix fun BitMasked<Long>.and(m: Long): Long = this.mask and m
infix fun BitMasked<Long>.or(m: Long): Long = this.mask or m
infix fun BitMasked<Long>.xor(m: Long): Long = this.mask xor m
fun BitMasked<Long>.not(): Long = this.mask.inv()
infix fun BitMasked<Long>.shl(bits: Int): Long = this.mask shl bits
infix fun BitMasked<Long>.shr(bits: Int): Long = this.mask shr bits

// Long >> BitMasked<Long>
infix fun Long.and(bm: BitMasked<Long>): Long = this and bm.mask
infix fun Long.or(bm: BitMasked<Long>): Long = this or bm.mask
infix fun Long.xor(bm: BitMasked<Long>): Long = this xor bm.mask

// Range
infix fun BitMasked<Long>.rangeTo(other: BitMasked<Long>): LongRange = this.mask..other.mask

// Bitwise helpers
@JvmName("getBit")
fun BitMasked<Long>.getBit(bit: Int): Boolean = (this.mask and (1L shl bit)) != 0L
@JvmName("setBit")
fun BitMasked<Long>.setBit(bit: Int): Long = this.mask or (1L shl bit)
@JvmName("clearBit")
fun BitMasked<Long>.clearBit(bit: Int): Long = this.mask and (1L shl bit).inv()
@JvmName("toggleBit")
fun BitMasked<Long>.toggleBit(bit: Int): Long = this.mask xor (1L shl bit)
@JvmName("rotateLeft")
fun BitMasked<Long>.rotateLeft(bits: Int): Long = (this.mask shl bits) or (this.mask ushr (64 - bits))
@JvmName("rotateRight")
fun BitMasked<Long>.rotateRight(bits: Int): Long = (this.mask ushr bits) or (this.mask shl (64 - bits))

// Boolean
infix fun BitMasked<Long>.andAlso(m: Long): Boolean = (this.mask and m) != 0L
infix fun BitMasked<Long>.orElse(m: Long): Boolean = (this.mask or m) != 0L
@JvmName("logicalNot")
fun BitMasked<Long>.logicalNot(): Boolean = this.mask == 0L

// Conversion
fun BitMasked<Long>.toLong(): Long = this.mask
@JvmName("toInt")
fun BitMasked<Long>.toInt(): Int = this.mask.toInt()
