package borg.trikeshed.context

/**
 * Lifecycle states for any [AsyncContextElement].
 *
 * Each state encodes its ordinal as a bitmask for efficient comparison and
 * alignment with semantic methods.
 */
enum class ElementState(val mask: Int) {
    CREATED(1 shl 0),
    OPEN(1 shl 1),
    CLOSING(1 shl 2),
    CLOSED(1 shl 3);

    fun isAtLeast(other: ElementState): Boolean = this.ordinal >= other.ordinal
    fun isAtMost(other: ElementState): Boolean = this.ordinal <= other.ordinal
    fun isLessThan(other: ElementState): Boolean = this.ordinal < other.ordinal
    fun isGreaterThan(other: ElementState): Boolean = this.ordinal > other.ordinal
}
