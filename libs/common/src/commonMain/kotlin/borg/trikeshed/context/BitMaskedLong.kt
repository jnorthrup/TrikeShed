package borg.trikeshed.context

/**
 * Interface for enums that represent Long bitmasks.
 */

interface BitMaskedLong
{
    val ordinal: Int
    val mask: Long get() = 1L shl ordinal

    fun isAtLeast(other: BitMaskedLong): Boolean = this.ordinal >= other.ordinal
    fun isAtMost(other: BitMaskedLong): Boolean = this.ordinal <= other.ordinal
    fun isLessThan(other: BitMaskedLong): Boolean = this.ordinal < other.ordinal
    fun isGreaterThan(other: BitMaskedLong): Boolean = this.ordinal > other.ordinal
}
