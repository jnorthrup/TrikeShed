package borg.trikeshed.context

/**
 * Interface for enums that represent bitmasks.
 */
interface BitMasked {
    val ordinal: Int
    val mask: UInt get() = 1u shl ordinal

    fun isAtLeast(other: BitMasked): Boolean = this.ordinal >= other.ordinal
    fun isAtMost(other: BitMasked): Boolean = this.ordinal <= other.ordinal
    fun isLessThan(other: BitMasked): Boolean = this.ordinal < other.ordinal
    fun isGreaterThan(other: BitMasked): Boolean = this.ordinal > other.ordinal
}
