
package borg.trikeshed.context

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

/** Alias so tests and callers can use either name. */
typealias ElementLifecycleState = ElementState
