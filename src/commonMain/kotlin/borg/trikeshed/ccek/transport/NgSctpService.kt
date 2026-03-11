package borg.trikeshed.ccek.transport

import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/**
 * SCTP-semantics transport CCEK service (Design 3 — ngSCTP spirit).
 *
 * Key invariants (pure protocol engineering, no AI/ML):
 * - Streams are independent — HOL blocking is per-stream, not cross-stream
 * - Multi-homing: multiple paths, automatic failover on path loss
 * - Message-oriented: ByteArray boundaries preserved end-to-end
 * - Chunk TLV: unknown chunk types skipped by length (parse-forward safety)
 * - Congestion control: deterministic only (cubic / hstcp / rack)
 */
data class NgSctpService(
    val streams: Map<Int, StreamHandle> = mutableMapOf(),
    val paths: List<String> = emptyList(),          // multi-homing: active path addresses
    val congestionControl: String = "cubic"          // cubic | hstcp | rack — deterministic only
) : StreamTransport {
    companion object Key : CoroutineContext.Key<NgSctpService>
    override val key: CoroutineContext.Key<*> get() = Key
    override suspend fun openStream(): StreamHandle {
        val streamId = (streams.keys.maxOrNull() ?: -1) + 1
        val streamHandle = StreamHandle(
            id = streamId,
            send = Channel(Channel.BUFFERED),
            recv = Channel(Channel.BUFFERED),
        )
        (streams as? MutableMap<Int, StreamHandle>)?.put(streamId, streamHandle)
        return streamHandle
    }
    override val activeStreams: Int get() = streams.size
}
