package borg.trikeshed.ccek.transport

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
    val streams: Map<Int, StreamHandle> = emptyMap(),
    val paths: List<String> = emptyList(),          // multi-homing: active path addresses
    val congestionControl: String = "cubic"          // cubic | hstcp | rack — deterministic only
) : StreamTransport {
    companion object Key : CoroutineContext.Key<NgSctpService>
    override val key: CoroutineContext.Key<*> get() = Key
    override suspend fun openStream(): StreamHandle = TODO("SCTP stream factory")
    override val activeStreams: Int get() = streams.size
}
