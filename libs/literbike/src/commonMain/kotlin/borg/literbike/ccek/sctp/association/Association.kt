package borg.literbike.ccek.sctp.association

import java.util.concurrent.atomic.AtomicInteger

/**
 * SCTP Association - endpoint state
 *
 * This module CANNOT see stream or chunk.
 */

/**
 * SCTP association states
 */
enum class AssociationState {
    Closed,
    CookieWait,
    CookieEchoed,
    Established,
    ShutdownPending,
    ShutdownReceived,
    ShutdownSent,
    ShutdownAckSent;

    companion object {
        fun fromInt(v: Int): AssociationState = when (v) {
            0 -> Closed
            1 -> CookieWait
            2 -> CookieEchoed
            3 -> Established
            4 -> ShutdownPending
            5 -> ShutdownReceived
            6 -> ShutdownSent
            7 -> ShutdownAckSent
            else -> Closed
        }
    }
}

/**
 * AssociationKey - SCTP association state machine
 */
object AssociationKey {
    val FACTORY: () -> AssociationElement = { AssociationElement() }
}

/**
 * AssociationElement - SCTP association state
 */
class AssociationElement {
    val state: AtomicInteger = AtomicInteger(AssociationState.Closed.ordinal)
    val activeTsn: AtomicInteger = AtomicInteger(0)

    fun state(): AssociationState = AssociationState.fromInt(state.get())

    fun setState(state: AssociationState) {
        this.state.set(state.ordinal)
    }
}
