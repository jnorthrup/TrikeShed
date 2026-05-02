package borg.trikeshed.context

// JVM-specific extensions with JvmName to avoid signature clashes
// These override the commonMain implementations on JVM

// BitMasked<UInt> boolean logic
@JvmName("andAlsoUInt")
inline infix fun <reified E> BitMasked<UInt>.andAlso(other: E): Boolean where E : Enum<E>, E : BitMasked<UInt> = (this.mask and other.mask) != 0u
@JvmName("orElseUInt")
inline infix fun <reified E> BitMasked<UInt>.orElse(other: E): Boolean where E : Enum<E>, E : BitMasked<UInt> = (this.mask or other.mask) != 0u
@JvmName("logicalNotUInt")
fun BitMasked<UInt>.logicalNot(): Boolean = this.mask == 0u

// BitMasked<UInt> conversion
@JvmName("toIntFromUInt")
fun BitMasked<UInt>.toInt(): Int = this.mask.toInt()
@JvmName("toLongFromUInt")
fun BitMasked<UInt>.toLong(): Long = this.mask.toLong()

// BitMasked<Long> comparisons
@JvmName("eqLong")
inline infix fun <reified E> BitMasked<Long>.eq(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = this.mask == other.mask
@JvmName("neLong")
inline infix fun <reified E> BitMasked<Long>.ne(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = this.mask != other.mask
@JvmName("ltLong")
inline infix fun <reified E> BitMasked<Long>.lt(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = this.mask < other.mask
@JvmName("gtLong")
inline infix fun <reified E> BitMasked<Long>.gt(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = this.mask > other.mask
@JvmName("leLong")
inline infix fun <reified E> BitMasked<Long>.le(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = this.mask <= other.mask
@JvmName("geLong")
inline infix fun <reified E> BitMasked<Long>.ge(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = this.mask >= other.mask

// BitMasked<Long> boolean logic
@JvmName("andAlsoLong")
inline infix fun <reified E> BitMasked<Long>.andAlso(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = (this.mask and other.mask) != 0L
@JvmName("orElseLong")
inline infix fun <reified E> BitMasked<Long>.orElse(other: E): Boolean where E : Enum<E>, E : BitMasked<Long> = (this.mask or other.mask) != 0L
@JvmName("logicalNotLong")
fun BitMasked<Long>.logicalNot(): Boolean = this.mask == 0L

// BitMasked<Long> conversion
@JvmName("toIntFromLong")
fun BitMasked<Long>.toInt(): Int = this.mask.toInt()
@JvmName("toLongFromLong")
fun BitMasked<Long>.toLong(): Long = this.mask

// BitMasked<Int> boolean logic
@JvmName("andAlsoInt")
inline infix fun <reified E> BitMasked<Int>.andAlso(other: E): Boolean where E : Enum<E>, E : BitMasked<Int> = (this.mask and other.mask) != 0
@JvmName("orElseInt")
inline infix fun <reified E> BitMasked<Int>.orElse(other: E): Boolean where E : Enum<E>, E : BitMasked<Int> = (this.mask or other.mask) != 0
@JvmName("logicalNotInt")
fun BitMasked<Int>.logicalNot(): Boolean = this.mask == 0

// BitMasked<Int> conversion
@JvmName("toLongFromInt")
fun BitMasked<Int>.toLong(): Long = this.mask.toLong()
