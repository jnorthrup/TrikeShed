package borg.trikeshed.context

import borg.trikeshed.lib.*

// ── CCEK: Coroutine → Context → Element → Key ──────────────────
//
// The macroscopic shape. Each level is a Join.
// CCEK is the algebra that binds Families 1-3 to execution scope.

/**
 * LifeK — lifecycle facet keys under OpK.
 */
sealed class LifeK<out R> : OpK<R>() {
    data object FanOut : LifeK<Series<Any?>>()
}

/**
 * Drain stops admission, finishes in-flight work, joins children and closes channels.
 * The one lifecycle contract a holder of mixed elements calls.
 */
fun interface Drain {
    suspend fun drain()
}

// ── Interest BitMask ────────────────────────────────────────────

/** IO readiness interests — BitMasked<UInt>. */
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
