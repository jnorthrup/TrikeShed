package borg.trikeshed.context

import borg.trikeshed.lib.*

// ── CCEK: Coroutine → Context → Element → Key ──────────────────
//
// The macroscopic shape. Each level is a Join.
// CCEK is the algebra that binds Families 1-3 to execution scope.

/**
 * Ordered lifecycle labels used by element admission and cleanup checks.
 * Each owner implements its transitions and synchronization.
 */
enum class ElementState(override val mask: UInt) : BitMasked<UInt> {
    CREATED(1u shl 0),
    OPEN(1u shl 1),
    ACTIVE(1u shl 2),
    DRAINING(1u shl 3),
    CLOSED(1u shl 4);
}

/**
 * LifeK — lifecycle facet keys under OpK.
 */
sealed class LifeK<out R> : OpK<R>() {
    data object State  : LifeK<ElementState>()
    data object FanOut : LifeK<Series<Any?>>()
}

// ── Interest BitMask ────────────────────────────────────────────

/** IO readiness interests — BitMasked<UInt> like ElementState. */
enum class Interest(override val mask: UInt) : BitMasked<UInt> {
    READ(1u shl 0),
    WRITE(1u shl 1),
    ACCEPT(1u shl 2),
    CONNECT(1u shl 3),
    ERROR(1u shl 4);

    companion object {
        fun fromMask(mask: UInt): Set<Interest> =
            entries.filter { (mask and it.mask) != 0u }.toSet()

        fun toMask(interests: Set<Interest>): UInt =
            interests.fold(0u) { acc, i -> acc or i.mask }
    }
}
