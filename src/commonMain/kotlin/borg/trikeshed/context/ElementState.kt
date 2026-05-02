
package borg.trikeshed.context

/**
 * Lifecycle states for any [AsyncContextElement].
 */
enum class ElementState : BitMasked<UInt> {
    CREATED,
    OPEN,
    ACTIVE,
    DRAINING,
    CLOSED;

    override val mask: UInt get() = 1u shl ordinal
}

/** Alias so tests and callers can use either name. */
typealias ElementLifecycleState = ElementState
