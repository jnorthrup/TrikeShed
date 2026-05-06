package borg.trikeshed.context

// BitMasked<Int> boolean logic (Int only available on JVM via BigInteger)
@JvmName("andAlsoInt")
inline infix fun <reified E> BitMasked<Int>.andAlso(other: E): Boolean where E : Enum<E>, E : BitMasked<Int> = (this.mask and other.mask) != 0
@JvmName("orElseInt")
inline infix fun <reified E> BitMasked<Int>.orElse(other: E): Boolean where E : Enum<E>, E : BitMasked<Int> = (this.mask or other.mask) != 0
@JvmName("logicalNotInt")
fun BitMasked<Int>.logicalNot(): Boolean = this.mask == 0

// BitMasked<Int> conversion
@JvmName("toLongFromInt")
fun BitMasked<Int>.toLong(): Long = this.mask.toLong()
