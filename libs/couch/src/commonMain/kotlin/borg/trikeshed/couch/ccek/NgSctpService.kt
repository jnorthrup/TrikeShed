package borg.trikeshed.ccek.transport

import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** Multi-homing path state for SCTP failover (RFC 4960 §6.4). */
enum class PathState {
    /** Path is actively used for data transmission. */
    ACTIVE,
    /** Path has failed heartbeats and is not used. */
    INACTIVE,
    /** Path has not yet been probed. */
    UNKNOWN,
}

/**
 * Per-path status for multi-homing failover tracking.
 *
 * @param address The destination address (host:port or IP).
 * @param state Current path state.
 * @param failures Consecutive heartbeat failures on this path.
 */
data class PathStatus(
    val address: String,
    val state: PathState = PathState.UNKNOWN,
    val failures: Int = 0,
)

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

    /** Per-path failover tracking — lazy-initialized from [paths] on first access. */
    private val _pathStatuses: MutableMap<String, PathStatus> by lazy {
        paths.associateTo(mutableMapOf()) { it to PathStatus(address = it) }
    }

    /** Current primary path — first ACTIVE path, or null if none are active. */
    val primaryPath: PathStatus?
        get() = _pathStatuses.values.firstOrNull { it.state == PathState.ACTIVE }
            ?: _pathStatuses.values.firstOrNull { it.state == PathState.UNKNOWN }

    /**
     * Mark [failedPath] as INACTIVE and return the next available ACTIVE path
     * for failover. Returns null if no paths remain.
     */
    fun failover(failedPath: String): PathStatus? {
        val current = _pathStatuses[failedPath] ?: return primaryPath
        _pathStatuses[failedPath] = current.copy(
            state = PathState.INACTIVE,
            failures = current.failures + 1,
        )
        return primaryPath
    }

    /** Mark [path] as ACTIVE (recovery after successful heartbeat probe). */
    fun recoverPath(path: String): PathStatus {
        val current = _pathStatuses[path] ?: PathStatus(address = path)
        val recovered = current.copy(
            state = PathState.ACTIVE,
            failures = 0,
        )
        _pathStatuses[path] = recovered
        return recovered
    }

    /** All path statuses, indexed by address. */
    val pathStatuses: Map<String, PathStatus> get() = _pathStatuses

    // Public handle as non-negative Long (stream ID)
    var handle: Long = 0
        private set

    // Backing map for stream handles
    private val _streams: MutableMap<Int, StreamHandle> = streams

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
