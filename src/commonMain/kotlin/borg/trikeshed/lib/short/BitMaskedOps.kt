@file:JvmName("BitMaskedOpsShort")
package borg.trikeshed.lib.short

import borg.trikeshed.lib.BitMasked

// Short has no native bitwise ops — promoted via Int

// BitMasked<Short> >> BitMasked<Short>
inline infix fun <reified E> BitMasked<Short>.and(other: E): Short where E : Enum<E>, E : BitMasked<Short> =
    ((mask.toInt() and other.mask.toInt()).toShort())
inline infix fun <reified E> BitMasked<Short>.or(other: E): Short where E : Enum<E>, E : BitMasked<Short> =
    ((mask.toInt() or other.mask.toInt()).toShort())
inline infix fun <reified E> BitMasked<Short>.xor(other: E): Short where E : Enum<E>, E : BitMasked<Short> =
    ((mask.toInt() xor other.mask.toInt()).toShort())

// BitMasked<Short> >> Short
infix fun BitMasked<Short>.and(m: Short): Short = ((mask.toInt() and m.toInt()).toShort())
infix fun BitMasked<Short>.or(m: Short): Short = ((mask.toInt() or m.toInt()).toShort())
infix fun BitMasked<Short>.xor(m: Short): Short = ((mask.toInt() xor m.toInt()).toShort())
fun BitMasked<Short>.not(): Short = mask.toInt().inv().toShort()
infix fun BitMasked<Short>.shl(bits: Int): Short = ((mask.toInt() shl bits).toShort())
infix fun BitMasked<Short>.shr(bits: Int): Short = ((mask.toInt() shr bits).toShort())

// Short >> BitMasked<Short>
infix fun Short.and(bm: BitMasked<Short>): Short = ((toInt() and bm.mask.toInt()).toShort())
infix fun Short.or(bm: BitMasked<Short>): Short = ((toInt() or bm.mask.toInt()).toShort())
infix fun Short.xor(bm: BitMasked<Short>): Short = ((toInt() xor bm.mask.toInt()).toShort())

// Range
infix fun BitMasked<Short>.rangeTo(other: BitMasked<Short>): IntRange = mask.toInt()..other.mask.toInt()

// Bitwise helpers
@JvmName("getBit")
fun BitMasked<Short>.getBit(bit: Int): Boolean = (mask.toInt() and (1 shl bit)) != 0
@JvmName("setBit")
fun BitMasked<Short>.setBit(bit: Int): Short = (mask.toInt() or (1 shl bit)).toShort()
@JvmName("clearBit")
fun BitMasked<Short>.clearBit(bit: Int): Short = (mask.toInt() and (1 shl bit).inv()).toShort()
@JvmName("toggleBit")
fun BitMasked<Short>.toggleBit(bit: Int): Short = (mask.toInt() xor (1 shl bit)).toShort()
@JvmName("rotateLeft")
fun BitMasked<Short>.rotateLeft(bits: Int): Short =
    (((mask.toInt() shl bits) or (mask.toInt() ushr (16 - bits))).toShort())
@JvmName("rotateRight")
fun BitMasked<Short>.rotateRight(bits: Int): Short =
    (((mask.toInt() ushr bits) or (mask.toInt() shl (16 - bits))).toShort())

// Boolean logic
infix fun BitMasked<Short>.andAlso(m: Short): Boolean = (mask.toInt() and m.toInt()) != 0
infix fun BitMasked<Short>.orElse(m: Short): Boolean = (mask.toInt() or m.toInt()) != 0
@JvmName("logicalNot")
fun BitMasked<Short>.logicalNot(): Boolean = mask.toInt() == 0

// Conversion
fun BitMasked<Short>.toShort(): Short = this.mask
@JvmName("toInt")
fun BitMasked<Short>.toInt(): Int = this.mask.toInt()
