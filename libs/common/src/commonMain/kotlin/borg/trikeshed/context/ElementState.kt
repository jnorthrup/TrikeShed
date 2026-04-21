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

/**
 * Interface for enums that represent Long bitmasks.
 */
interface BitMaskedLong {
    val ordinal: Int
    val mask: Long get() = 1L shl ordinal

    fun isAtLeast(other: BitMaskedLong): Boolean = this.ordinal >= other.ordinal
    fun isAtMost(other: BitMaskedLong): Boolean = this.ordinal <= other.ordinal
    fun isLessThan(other: BitMaskedLong): Boolean = this.ordinal < other.ordinal
    fun isGreaterThan(other: BitMaskedLong): Boolean = this.ordinal > other.ordinal
}

/**
 * Lifecycle states for any [AsyncContextElement].
 */
enum class ElementState : BitMasked {
    CREATED,
    OPEN,
    ACTIVE,
    DRAINING,
    CLOSED;
}
