package borg.trikeshed.ccek.transport

import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
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
    val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
    val paths: List<String> = emptyList(),          // multi-homing: active path addresses
    val congestionControl: String = "cubic"          // cubic | hstcp | rack — deterministic only
) : StreamTransport {
    companion object Key : CoroutineContext.Key<NgSctpService>

    // Public handle as non-negative Long (stream ID)
    var handle: Long = 0
       set

    // Backing map for stream handles
   val _streams: MutableMap<Int, StreamHandle> = streams

    override val key: CoroutineContext.Key<*> get() = Key

    override suspend fun openStream(): StreamHandle {
        val id = _streams.keys.maxOfOrNull { it }?.let { it + 1 } ?: 0
        require(id >= 0) { "Stream ID must be non-negative" }

        val send = Channel<ByteArray>(Channel.BUFFERED)
        val recv = Channel<ByteArray>(Channel.BUFFERED)

        val streamHandle = StreamHandle(id, send, recv)
        _streams[id] = streamHandle

        // Update handle to non-negative next id
        handle = id.toLong() + 1

        return streamHandle
    }

    override val activeStreams: Int get() = _streams.size
}
