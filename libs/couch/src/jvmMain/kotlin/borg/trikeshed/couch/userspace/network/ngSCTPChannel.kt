package borg.trikeshed.couch.userspace.network

import borg.trikeshed.couch.htx.HtxBlock
import borg.trikeshed.couch.userspace.nio.SessionContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * ngSCTP — Next-Generation SCTP over HTX blocks.
 *
 * SCTP association: mapped to multiple ngSCTPChannel instances over one SCTP stream.
 * HTX Channelization: each HTX block IS a channel message.
 * Session stickyness: each session IS a CoroutineContext.Element keyed by SessionContextKey.
 *
 * A ngSCTPChannel IS a CoroutineContext.Element (it carries its session context).
 */
class ngSCTPChannel(
    val sessionId: String,
) : AbstractCoroutineContextElement(ngSCTPChannelKey) {

    /** Per-stream send channel — rendezvous so sender suspends until receiver is ready. */
    private val sendChannel = Channel<HtxBlock>(Channel.RENDEZVOUS)

    /** Per-stream recv channel — rendezvous so receiver suspends until sender is ready. */
    private val recvChannel = Channel<HtxBlock>(Channel.RENDEZVOUS)

    /** Bidirectional stream channel — alias for send side. */
    val channel: Channel<HtxBlock> get() = sendChannel

    /** Session context element for injection into branch coroutines. */
    val sessionContext: SessionContext = SessionContext(sessionId)

    /**
     * Send an HTX block on this stream.
     * Suspends until the receiver is ready (rendezvous).
     */
    suspend fun send(block: HtxBlock): Result<Unit> = runCatching {
        sendChannel.send(block)
    }

    /**
     * Receive an HTX block on this stream.
     * Suspends until the sender is ready (rendezvous).
     */
    suspend fun recv(): Result<HtxBlock> = runCatching {
        recvChannel.receive()
    }

    /**
     * Stream as a Flow — for use with collect{} etc.
     * The flow emits received blocks until the channel is closed.
     */
    fun stream(): Flow<HtxBlock> = recvChannel.consumeAsFlow()

    companion object {
        /** Singleton key for ngSCTPChannel in CoroutineContext. */
        object ngSCTPChannelKey : CoroutineContext.Key<ngSCTPChannel>

        /**
         * Factory: open a ngSCTPChannel for a given session.
         * The channel is registered as a named branch on the ReactorSupervisor
         * by the caller via reactor.launchBranch().
         */
        fun open(sessionId: String): ngSCTPChannel = ngSCTPChannel(sessionId)
    }
}

/**
 * Multiplexed SCTP-like transport over HTX channels.
 *
 * Wraps one underlying SCTP association and demultiplexes to multiple
 * ngSCTPChannel streams, each identified by sessionId.
 *
 * The session context (SessionContext CoroutineContext.Element) is
 * the stickyness key — injected into all session-scoped coroutines.
 */
class ngSCTPMultiplexer(
    private val realm: String,
) {
    private val _channels = mutableMapOf<String, ngSCTPChannel>()

    /** Look up or create a session channel by sessionId. */
    fun channel(sessionId: String): ngSCTPChannel =
        _channels.getOrPut(sessionId) { ngSCTPChannel.open(sessionId) }

    /** All open sessions. */
    val sessions: Map<String, ngSCTPChannel> get() = _channels.toMap()

    /** Number of active sessions. */
    val size: Int get() = _channels.size
}
