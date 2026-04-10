package borg.literbike.ccek.quic

// ============================================================================
// QUIC Congruence Layer -- ported from congruence.rs
// Maps QUIC concepts to io_uring facade
// Enables identical patterns across kernel io_uring and userspace implementations
// ============================================================================

/**
 * Stream state for congruent connection
 */
data class CongruentStreamState(
    val streamId: ULong,
    var state: StreamState = StreamState.Idle,
    var sendOffset: ULong = 0uL,
    var recvOffset: ULong = 0uL,
    var sendWindow: UInt = 65536u,
    var recvWindow: UInt = 65536u
)

/**
 * Flow control state for congruent connection
 */
data class FlowControlState(
    var connectionWindow: UInt = 1_048_576u,  // 1MB initial window
    var maxStreams: ULong = 1000uL,
    var activeStreams: ULong = 0uL
)

/**
 * QUIC Congruent Stream.
 * Ported from QuicCongruentStream.
 */
class QuicCongruentStream(
    val streamId: ULong,
    private val connection: QuicCongruentConnection
) {
    /** Read data from the stream */
    suspend fun read(maxBytes: Int): Result<List<UByte>> = runCatching {
        connection.readStream(streamId, maxBytes)
    }

    /** Write data to the stream */
    suspend fun write(data: List<UByte>): Result<Unit> = runCatching {
        connection.writeStream(streamId, data)
    }

    /** Close the stream */
    suspend fun close(): Result<Unit> = runCatching {
        connection.closeStream(streamId)
    }
}

/**
 * QUIC Congruent Connection.
 * Ported from QuicCongruentConnection.
 *
 * Works identically on kernel io_uring and userspace implementations.
 * Based on endgame principle: userspace control plane, kernel execution plane.
 */
class QuicCongruentConnection(
    val connectionId: ULong
) {
    private val streams = mutableMapOf<ULong, CongruentStreamState>()
    private var flowControl = FlowControlState()

    companion object {
        /** Create new QUIC-congruent connection */
        suspend fun create(connectionId: ULong): Result<QuicCongruentConnection> = runCatching {
            QuicCongruentConnection(connectionId)
        }
    }

    /** Open new bidirectional stream (QUIC-style) */
    suspend fun openStream(streamId: ULong): Result<QuicCongruentStream> = runCatching {
        // Check flow control limits
        if (flowControl.activeStreams >= flowControl.maxStreams) {
            throw IllegalStateException("Max streams exceeded")
        }
        flowControl.activeStreams++

        // Initialize stream state (control plane)
        val streamState = CongruentStreamState(
            streamId = streamId,
            state = StreamState.Open,
            sendOffset = 0uL,
            recvOffset = 0uL,
            sendWindow = 65536u,
            recvWindow = 65536u
        )

        streams[streamId] = streamState

        QuicCongruentStream(streamId, this)
    }

    /** Write data to a stream */
    suspend fun writeStream(streamId: ULong, data: List<UByte>): Result<Unit> = runCatching {
        val stream = streams[streamId]
            ?: throw IllegalStateException("Stream $streamId not found")

        // Check flow control
        if (data.size.toUInt() > stream.sendWindow) {
            throw IllegalStateException("Flow control window exceeded")
        }

        stream.sendOffset += data.size.toULong()
        // In production: submit write to io_uring facade
    }

    /** Read data from a stream */
    suspend fun readStream(streamId: ULong, maxBytes: Int): Result<List<UByte>> = runCatching {
        val stream = streams[streamId]
            ?: throw IllegalStateException("Stream $streamId not found")

        // In production: submit read to io_uring facade
        // For now, return empty
        emptyList<UByte>()
    }

    /** Close a stream */
    suspend fun closeStream(streamId: ULong): Result<Unit> = runCatching {
        streams[streamId]?.state = StreamState.Closed
        flowControl.activeStreams = (flowControl.activeStreams - 1uL).coerceAtLeast(0uL)
    }

    /** Get stream state */
    fun getStreamState(streamId: ULong): CongruentStreamState? = streams[streamId]

    /** Get all active stream IDs */
    fun getActiveStreams(): List<ULong> = streams.keys.toList()

    /** Get flow control state */
    fun getFlowControl(): FlowControlState = flowControl.copy()
}

/**
 * QUIC Congruence Manager.
 * Manages multiple congruent connections with shared io_uring facade.
 */
class QuicCongruenceManager {
    private val connections = mutableMapOf<ULong, QuicCongruentConnection>()

    /** Create a new congruent connection */
    suspend fun createConnection(connectionId: ULong): Result<QuicCongruentConnection> = runCatching {
        val conn = QuicCongruentConnection.create(connectionId).getOrThrow()
        connections[connectionId] = conn
        conn
    }

    /** Get connection by ID */
    fun getConnection(connectionId: ULong): QuicCongruentConnection? = connections[connectionId]

    /** Remove a connection */
    suspend fun removeConnection(connectionId: ULong): Result<Unit> = runCatching {
        connections.remove(connectionId)
    }

    /** Get all connection IDs */
    fun getConnectionIds(): List<ULong> = connections.keys.toList()

    /** Get total active streams across all connections */
    fun getTotalActiveStreams(): ULong = connections.values.sumOf { it.getActiveStreams().size.toULong() }
}
