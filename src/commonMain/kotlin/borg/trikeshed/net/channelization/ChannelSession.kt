package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import kotlin.jvm.JvmInline

/**
 * Opaque session identifier for channelization.
 */
@JvmInline
value class ChannelSessionId(val raw: String)

/**
 * Lifecycle state for a channel session.
 */
sealed interface ChannelSessionState {
    /** Session created, not yet activated. */
    object Initialized : ChannelSessionState

    /** Session active and processing frames. */
    object Active : ChannelSessionState

    /** Session draining, no new frames accepted. */
    object Draining : ChannelSessionState

    /** Session terminated. */
    object Terminated : ChannelSessionState

    /** Session failed with error. */
    data class Failed(val reason: Throwable) : ChannelSessionState
}

/**
 * Core channel session abstraction.
 *
 * Represents an identity and lifecycle container for a channelized
 * communication session. Transport and protocol-agnostic by design.
 */
interface ChannelSession {
    val id: ChannelSessionId
    val protocol: ProtocolId
    val state: ChannelSessionState

    /** Transition session to a new state. */
    fun transitionTo(newState: ChannelSessionState)

    /** Check if session can accept new frames. */
    fun canAcceptFrames(): Boolean = state == ChannelSessionState.Active
}
