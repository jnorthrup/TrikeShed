package borg.trikeshed.context

/**
 * Minimal lifecycle enum for couch-side trainer code.
 *
 * Keep the ordinal ordering compatible with the shared context module:
 * CREATED < OPEN < ACTIVE < DRAINING < CLOSED.
 */
enum class ElementState {
    CREATED,
    OPEN,
    ACTIVE,
    DRAINING,
    CLOSED;

    fun isAtLeast(other: ElementState): Boolean = ordinal >= other.ordinal
    fun isLessThan(other: ElementState): Boolean = ordinal < other.ordinal
}
