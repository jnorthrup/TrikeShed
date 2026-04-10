package borg.literbike.ccek.quic

// ============================================================================
// QUIC Stream -- ported from quic_stream.rs
// Priority-aware stream multiplexer scheduler
// ============================================================================

/**
 * QUIC Stream wrapper with priority support.
 * Ported from Rust QuicStream.
 */
class QuicStream(
    val streamId: ULong,
    private val engine: QuicEngine,
    val remoteAddr: String,
    var priority: StreamPriority = StreamPriority.Normal
) {
    private val sendBuffer = mutableListOf<UByte>()

    /** Set the priority of this stream */
    fun setPriority(newPriority: StreamPriority) {
        priority = newPriority
        engine.setStreamPriority(streamId, newPriority)
    }

    /** Get the current priority of this stream */
    fun getPriority(): StreamPriority = priority

    /** Write data to the stream */
    suspend fun write(data: List<UByte>): Result<Unit> = runCatching {
        engine.sendStreamDataPriority(streamId, data, priority)
    }

    /** Finish the stream (send FIN) */
    suspend fun finish(): Result<Unit> = runCatching {
        engine.sendStreamFin(streamId)
    }

    /** Read up to max bytes as a chunk */
    fun readChunk(max: Int): List<UByte>? {
        return engine.drainStreamRecv(streamId, max)
    }

    /** Get stream statistics */
    fun stats(): StreamStats? = engine.getStreamStats(streamId)
}

/** Statistics for a QUIC stream */
data class StreamStats(
    val streamId: ULong,
    val bytesSent: ULong,
    val bytesReceived: ULong,
    val sendOffset: ULong,
    val receiveOffset: ULong,
    val state: StreamState,
    val priority: StreamPriority
)

/** Pending write entry in the stream scheduler queue */
data class ScheduledWrite(
    val streamId: ULong,
    val data: List<UByte>,
    val priority: StreamPriority
)

/**
 * Priority-aware stream multiplexer scheduler.
 *
 * Callers enqueue writes with push; the scheduler drains them in
 * descending priority order (Critical > High > Normal > Background),
 * with stable FIFO ordering within the same priority tier.
 */
class StreamScheduler {
    private val queue = mutableListOf<ScheduledWrite>()

    /** Enqueue a pending write */
    fun push(streamId: ULong, data: List<UByte>, priority: StreamPriority) {
        queue.add(ScheduledWrite(streamId, data, priority))
    }

    /**
     * Drain up to limit writes in priority order (highest first, FIFO within tier).
     * Returns the drained entries; remaining entries stay queued.
     */
    fun drainNext(limit: Int): List<ScheduledWrite> {
        // Stable sort: highest priorityValue first, FIFO within same tier preserved
        queue.sortWith(compareByDescending<ScheduledWrite> { it.priority.value })
        val count = minOf(limit, queue.size)
        val drained = queue.subList(0, count).toList()
        queue.subList(0, count).clear()
        return drained
    }

    /** Number of pending writes */
    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()
}
