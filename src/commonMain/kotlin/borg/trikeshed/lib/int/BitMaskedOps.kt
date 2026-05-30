@file:JvmName("BitMaskedOpsInt")
package borg.trikeshed.lib.int

import borg.trikeshed.lib.BitMasked

inline infix fun <reified E> BitMasked<Int>.and(other: E): Int where E : Enum<E>, E : BitMasked<Int> = this.mask and other.mask
inline infix fun <reified E> BitMasked<Int>.or(other: E): Int where E : Enum<E>, E : BitMasked<Int> = this.mask or other.mask
inline infix fun <reified E> BitMasked<Int>.xor(other: E): Int where E : Enum<E>, E : BitMasked<Int> = this.mask xor other.mask

infix fun BitMasked<Int>.and(m: Int): Int = this.mask and m
infix fun BitMasked<Int>.or(m: Int): Int = this.mask or m
infix fun BitMasked<Int>.xor(m: Int): Int = this.mask xor m
fun BitMasked<Int>.not(): Int = this.mask.inv()
infix fun BitMasked<Int>.shl(bits: Int): Int = this.mask shl bits
infix fun BitMasked<Int>.shr(bits: Int): Int = this.mask shr bits

infix fun Int.and(bm: BitMasked<Int>): Int = this and bm.mask
infix fun Int.or(bm: BitMasked<Int>): Int = this or bm.mask
infix fun Int.xor(bm: BitMasked<Int>): Int = this xor bm.mask

infix fun BitMasked<Int>.rangeTo(other: BitMasked<Int>): IntRange = this.mask..other.mask

@JvmName("getBit")
fun BitMasked<Int>.getBit(bit: Int): Boolean = (this.mask and (1 shl bit)) != 0
@JvmName("setBit")
fun BitMasked<Int>.setBit(bit: Int): Int = this.mask or (1 shl bit)
@JvmName("clearBit")
fun BitMasked<Int>.clearBit(bit: Int): Int = this.mask and (1 shl bit).inv()
@JvmName("toggleBit")
fun BitMasked<Int>.toggleBit(bit: Int): Int = this.mask xor (1 shl bit)
@JvmName("rotateLeft")
fun BitMasked<Int>.rotateLeft(bits: Int): Int = (this.mask shl bits) or (this.mask ushr (32 - bits))
@JvmName("rotateRight")
fun BitMasked<Int>.rotateRight(bits: Int): Int = (this.mask ushr bits) or (this.mask shl (32 - bits))

infix fun BitMasked<Int>.andAlso(m: Int): Boolean = (this.mask and m) != 0
infix fun BitMasked<Int>.orElse(m: Int): Boolean = (this.mask or m) != 0
@JvmName("logicalNot")
fun BitMasked<Int>.logicalNot(): Boolean = this.mask == 0

fun BitMasked<Int>.toInt(): Int = this.mask
