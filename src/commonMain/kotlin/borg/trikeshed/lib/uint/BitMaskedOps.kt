package borg.trikeshed.lib.uint

import borg.trikeshed.lib.BitMasked

// BitMasked<UInt> >> BitMasked<UInt>
inline infix fun <reified E> BitMasked<UInt>.and(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask and other.mask
inline infix fun <reified E> BitMasked<UInt>.or(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask or other.mask
inline infix fun <reified E> BitMasked<UInt>.xor(other: E): UInt where E : Enum<E>, E : BitMasked<UInt> = this.mask xor other.mask

// BitMasked<UInt> >> UInt
infix fun BitMasked<UInt>.and(mask: UInt): UInt = this.mask and mask
infix fun BitMasked<UInt>.or(mask: UInt): UInt = this.mask or mask
infix fun BitMasked<UInt>.xor(mask: UInt): UInt = this.mask xor mask
fun BitMasked<UInt>.not(): UInt = this.mask.inv()
infix fun BitMasked<UInt>.shl(bits: Int): UInt = this.mask shl bits
infix fun BitMasked<UInt>.shr(bits: Int): UInt = this.mask shr bits

// UInt >> BitMasked<UInt>
infix fun UInt.and(bm: BitMasked<UInt>): UInt = this and bm.mask
infix fun UInt.or(bm: BitMasked<UInt>): UInt = this or bm.mask
infix fun UInt.xor(bm: BitMasked<UInt>): UInt = this xor bm.mask

// Range
infix fun BitMasked<UInt>.rangeTo(other: BitMasked<UInt>): UIntRange = this.mask..other.mask
infix fun Int.rangeTo(bm: BitMasked<UInt>): IntRange = this..bm.mask.toInt()

// Bitwise helpers
fun BitMasked<UInt>.getBit(bit: Int): Boolean = (this.mask and (1u shl bit)) != 0u
fun BitMasked<UInt>.setBit(bit: Int): UInt = this.mask or (1u shl bit)
fun BitMasked<UInt>.clearBit(bit: Int): UInt = this.mask and (1u shl bit).inv()
fun BitMasked<UInt>.toggleBit(bit: Int): UInt = this.mask xor (1u shl bit)
fun BitMasked<UInt>.rotateLeft(bits: Int): UInt = (this.mask shl bits) or (this.mask shr (32 - bits))
fun BitMasked<UInt>.rotateRight(bits: Int): UInt = (this.mask shr bits) or (this.mask shl (32 - bits))

// Boolean logic
infix fun BitMasked<UInt>.andAlso(mask: UInt): Boolean = (this.mask and mask) != 0u
infix fun BitMasked<UInt>.orElse(mask: UInt): Boolean = (this.mask or mask) != 0u
fun BitMasked<UInt>.logicalNot(): Boolean = this.mask == 0u

// Conversion
fun BitMasked<UInt>.toUInt(): UInt = this.mask
fun BitMasked<UInt>.toInt(): Int = this.mask.toInt()
fun BitMasked<UInt>.toLong(): Long = this.mask.toLong()
