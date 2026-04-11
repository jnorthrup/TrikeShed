package borg.literbike.ccek.quic

/**
 * QUIC Engine - simplified connection and stream processing.
 * Ported from literbike/src/ccek/quic/src/engine.rs.
 *
 * Note: The Rust version uses tokio UdpSocket and parking_lot Mutex.
 * This Kotlin version provides the same API surface using Kotlin concurrency primitives.
 */

/**
 * Connection role - client or server.
 */
enum class EngineRole {
    Client,
    Server
}

/**
 * Stream state within a QUIC connection.
 */
data class QuicStreamState(
    val streamId: Long,
    val sendBuffer: MutableList<Byte> = mutableListOf(),
    val receiveBuffer: MutableList<Byte> = mutableListOf(),
    var sendOffset: Long = 0,
    var receiveOffset: Long = 0,
    var maxData: Long = 0,
    var state: StreamState = StreamState.Idle
)

/**
 * QUIC Engine for processing packets and managing streams.
 */
class QuicEngine(
    private val role: EngineRole,
    private val initialState: QuicConnectionState,
    private val privateKey: ByteArray = ByteArray(0)
) {
    private val state: QuicConnectionState = initialState.copy()
    private val streamStates = mutableMapOf<Long, QuicStreamState>()
    private val ackPending = mutableListOf<Long>()

    companion object {
        fun new(
            role: EngineRole,
            initialState: QuicConnectionState,
            privateKey: ByteArray = ByteArray(0)
        ): QuicEngine = QuicEngine(role, initialState, privateKey)
    }

    init {
        state.connectionState = ConnectionState.Handshaking
    }

    /**
     * Process a received QUIC packet.
     */
    fun processPacket(packet: QuicPacket): Result<ByteArray?> {
        // Process each frame
        for (frame in packet.frames) {
            when (frame) {
                is QuicFrame.Stream -> processStreamFrame(frame.frame)
                is QuicFrame.Ack -> processAckFrame(frame.frame)
                is QuicFrame.Crypto -> processCryptoFrame(frame.frame)
                else -> { /* Ignore other frame types */ }
            }
        }

        // Update state with received packet
        state.receivedPackets.add(packet)
        state.nextPacketNumber += 1

        // Generate ACK if needed
        return if (ackPending.isNotEmpty()) {
            val ackPacket = createAckPacket()
            ackPending.clear()
            // Return serialized ACK packet
            serializePacket(ackPacket)
        } else {
            Result.success(null)
        }
    }

    /**
     * Send stream data.
     */
    fun sendStreamData(streamId: Long, data: ByteArray): Result<ByteArray> {
        val stream = streamStates.getOrPut(streamId) {
            QuicStreamState(
                streamId = streamId,
                maxData = state.transportParams.maxStreamData,
                state = StreamState.Idle
            )
        }

        // Create stream frame
        val frame = StreamFrame(
            streamId = streamId,
            offset = stream.sendOffset,
            data = data,
            fin = false
        )

        // Update stream state
        stream.sendBuffer.addAll(data.toList())
        stream.sendOffset += data.size

        // Create packet
        val packet = QuicPacket(
            header = QuicHeader(
                type = QuicPacketType.ShortHeader,
                version = state.version,
                destinationConnectionId = state.remoteConnectionId,
                sourceConnectionId = state.localConnectionId,
                packetNumber = state.nextPacketNumber,
                token = null
            ),
            frames = listOf(QuicFrame.Stream(frame)),
            payload = data
        )

        // Update connection state
        state.sentPackets.add(packet)
        state.nextPacketNumber += 1
        state.bytesInFlight += data.size

        return serializePacket(packet)
    }

    /**
     * Create a new stream, returning its ID.
     */
    fun createStream(): Long {
        val newStreamId = state.nextStreamId
        state.nextStreamId += 1
        return newStreamId
    }

    private fun processStreamFrame(frame: StreamFrame) {
        val stream = streamStates.getOrPut(frame.streamId) {
            QuicStreamState(
                streamId = frame.streamId,
                maxData = state.transportParams.maxStreamData,
                state = StreamState.Idle
            )
        }

        // Update stream receive buffer
        stream.receiveBuffer.addAll(frame.data.toList())
        stream.receiveOffset = frame.offset + frame.data.size

        // Mark packet for ACK
        ackPending.add(state.nextPacketNumber - 1)
    }

    private fun processAckFrame(frame: AckFrame) {
        // Remove acknowledged packets from bytes in flight
        var ackedBytes = 0L
        for ((start, end) in frame.ackRanges) {
            ackedBytes += (end - start + 1) * 1350
        }
        state.bytesInFlight = (state.bytesInFlight - ackedBytes).coerceAtLeast(0)

        // Transition to Connected state after receiving an ACK
        if (state.connectionState == ConnectionState.Handshaking) {
            state.connectionState = ConnectionState.Connected
        }
    }

    private fun processCryptoFrame(frame: CryptoFrame) {
        // Process crypto data (simplified - would involve TLS in real implementation)
        ackPending.add(state.nextPacketNumber - 1)
    }

    private fun createAckPacket(): QuicPacket {
        val sortedAcks = ackPending.sorted()
        val ranges = mutableListOf<Pair<Long, Long>>()

        if (sortedAcks.isNotEmpty()) {
            var start = sortedAcks[0]
            var end = sortedAcks[0]

            for (v in sortedAcks.drop(1)) {
                if (v == end + 1) {
                    end = v
                } else {
                    ranges.add(start to end)
                    start = v
                    end = v
                }
            }
            ranges.add(start to end)
        }

        val ackFrame = AckFrame(
            largestAcknowledged = sortedAcks.lastOrNull() ?: 0,
            ackDelay = 0,
            ackRanges = ranges
        )

        return QuicPacket(
            header = QuicHeader(
                type = QuicPacketType.ShortHeader,
                version = state.version,
                destinationConnectionId = state.remoteConnectionId,
                sourceConnectionId = state.localConnectionId,
                packetNumber = state.nextPacketNumber,
                token = null
            ),
            frames = listOf(QuicFrame.Ack(ackFrame)),
            payload = ByteArray(0)
        )
    }

    fun getState(): QuicConnectionState = state.copy()

    fun setConnectionState(newState: ConnectionState) {
        state.connectionState = newState
    }

    fun getStream(streamId: Long): QuicStreamState? = streamStates[streamId]

    fun getActiveStreams(): List<Long> = streamStates.keys.toList()

    /**
     * Generate a key pair for the connection.
     */
    data class KeyPair(
        val private: ByteArray,
        val public: ByteArray
    )

    companion object {
        fun generateKeyPair(): KeyPair {
            val random = kotlin.random.Random
            val privateKey = ByteArray(32) { random.nextInt(256).toByte() }
            val publicKey = ByteArray(32) { random.nextInt(256).toByte() }
            return KeyPair(privateKey, publicKey)
        }
    }
}

/**
 * Serialize a QUIC packet to bytes (simplified).
 */
private fun serializePacket(packet: QuicPacket): Result<ByteArray> {
    return runCatching {
        val buffer = mutableListOf<Byte>()
        // Header type byte
        buffer.add(packet.type.value.toByte())
        // Version (4 bytes)
        buffer.add(((packet.header.version ushr 24) and 0xFF).toByte())
        buffer.add(((packet.header.version ushr 16) and 0xFF).toByte())
        buffer.add(((packet.header.version ushr 8) and 0xFF).toByte())
        buffer.add((packet.header.version and 0xFF).toByte())
        // Destination connection ID length + bytes
        buffer.add(packet.header.destinationConnectionId.size.toByte())
        buffer.addAll(packet.header.destinationConnectionId.toList())
        // Source connection ID length + bytes
        buffer.add(packet.header.sourceConnectionId.size.toByte())
        buffer.addAll(packet.header.sourceConnectionId.toList())
        // Packet number (8 bytes)
        buffer.add(((packet.header.packetNumber ushr 56) and 0xFF).toByte())
        buffer.add(((packet.header.packetNumber ushr 48) and 0xFF).toByte())
        buffer.add(((packet.header.packetNumber ushr 40) and 0xFF).toByte())
        buffer.add(((packet.header.packetNumber ushr 32) and 0xFF).toByte())
        buffer.add(((packet.header.packetNumber ushr 24) and 0xFF).toByte())
        buffer.add(((packet.header.packetNumber ushr 16) and 0xFF).toByte())
        buffer.add(((packet.header.packetNumber ushr 8) and 0xFF).toByte())
        buffer.add((packet.header.packetNumber and 0xFF).toByte())
        // Payload
        buffer.addAll(packet.payload.toList())
        buffer.toByteArray()
    }
}
