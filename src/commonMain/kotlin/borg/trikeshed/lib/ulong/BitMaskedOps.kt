@file:JvmName("BitMaskedOpsULong")
package borg.trikeshed.lib.ulong

import borg.trikeshed.lib.BitMasked

inline infix fun <reified E> BitMasked<ULong>.and(other: E): ULong where E : Enum<E>, E : BitMasked<ULong> = this.mask and other.mask
inline infix fun <reified E> BitMasked<ULong>.or(other: E): ULong where E : Enum<E>, E : BitMasked<ULong> = this.mask or other.mask
inline infix fun <reified E> BitMasked<ULong>.xor(other: E): ULong where E : Enum<E>, E : BitMasked<ULong> = this.mask xor other.mask

infix fun BitMasked<ULong>.and(m: ULong): ULong = this.mask and m
infix fun BitMasked<ULong>.or(m: ULong): ULong = this.mask or m
infix fun BitMasked<ULong>.xor(m: ULong): ULong = this.mask xor m
fun BitMasked<ULong>.not(): ULong = this.mask.inv()
infix fun BitMasked<ULong>.shl(bits: Int): ULong = this.mask shl bits
infix fun BitMasked<ULong>.shr(bits: Int): ULong = this.mask shr bits

infix fun ULong.and(bm: BitMasked<ULong>): ULong = this and bm.mask
infix fun ULong.or(bm: BitMasked<ULong>): ULong = this or bm.mask
infix fun ULong.xor(bm: BitMasked<ULong>): ULong = this xor bm.mask

infix fun BitMasked<ULong>.rangeTo(other: BitMasked<ULong>): ULongRange = this.mask..other.mask

@JvmName("getBit")
fun BitMasked<ULong>.getBit(bit: Int): Boolean = (this.mask and (1uL shl bit)) != 0uL
@JvmName("setBit")
fun BitMasked<ULong>.setBit(bit: Int): ULong = this.mask or (1uL shl bit)
@JvmName("clearBit")
fun BitMasked<ULong>.clearBit(bit: Int): ULong = this.mask and (1uL shl bit).inv()
@JvmName("toggleBit")
fun BitMasked<ULong>.toggleBit(bit: Int): ULong = this.mask xor (1uL shl bit)
@JvmName("rotateLeft")
fun BitMasked<ULong>.rotateLeft(bits: Int): ULong = (this.mask shl bits) or (this.mask shr (64 - bits))
@JvmName("rotateRight")
fun BitMasked<ULong>.rotateRight(bits: Int): ULong = (this.mask shr bits) or (this.mask shl (64 - bits))

infix fun BitMasked<ULong>.andAlso(m: ULong): Boolean = (this.mask and m) != 0uL
infix fun BitMasked<ULong>.orElse(m: ULong): Boolean = (this.mask or m) != 0uL
@JvmName("logicalNot")
fun BitMasked<ULong>.logicalNot(): Boolean = this.mask == 0uL

fun BitMasked<ULong>.toULong(): ULong = this.mask
@JvmName("toLong")
fun BitMasked<ULong>.toLong(): Long = this.mask.toLong()
