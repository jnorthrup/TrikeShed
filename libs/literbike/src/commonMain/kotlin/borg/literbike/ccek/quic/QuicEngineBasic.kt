package borg.literbike.ccek.quic

// ============================================================================
// QUIC Engine (Basic) -- ported from engine.rs
// Simplified engine with basic packet processing and ACK generation
// ============================================================================

/**
 * QUIC Engine Role (client or server).
 * Ported from Rust Role enum.
 */
enum class QuicEngineRole {
    Client,
    Server
}

/**
 * Basic QUIC Engine -- ported from engine.rs.
 *
 * Simplified implementation with:
 * - Basic packet processing
 * - ACK generation
 * - Stream data sending
 * - Stream creation
 */
class QuicEngineBasic(
    val role: QuicEngineRole,
    initialState: QuicConnectionState = QuicConnectionState()
) {
    private val state = initialState.copy().apply {
        connectionState = ConnectionState.Handshaking
    }
    private val streamStates = mutableMapOf<ULong, QuicStreamState>()
    private val ackPending = mutableListOf<ULong>()

    /**
     * Process an incoming packet.
     * Returns response packets (e.g., ACKs) that should be sent.
     */
    suspend fun processPacket(packet: QuicPacket): Result<List<QuicPacket>> = runCatching {
        val responses = mutableListOf<QuicPacket>()

        // Process each frame
        for (frame in packet.frames) {
            when (frame) {
                is QuicFrame.Stream -> {
                    processStreamFrame(frame.frame)
                }
                is QuicFrame.Ack -> {
                    processAckFrame(frame.frame)
                    // Transition to Connected state after receiving an ACK
                    if (state.connectionState == ConnectionState.Handshaking) {
                        state.connectionState = ConnectionState.Connected
                    }
                }
                is QuicFrame.Crypto -> {
                    processCryptoFrame(frame.frame)
                }
                else -> { /* ignore other frame types */ }
            }
        }

        // Update state with received packet
        state.receivedPackets.add(packet)
        state.nextPacketNumber++

        // Generate ACK if needed
        if (ackPending.isNotEmpty()) {
            responses.add(createAckPacket())
            ackPending.clear()
        }

        responses
    }

    /** Send data on a stream */
    suspend fun sendStreamData(streamId: ULong, data: List<UByte>): Result<QuicPacket> = runCatching {
        val stream = streamStates.getOrPut(streamId) {
            QuicStreamState(
                streamId = streamId,
                maxData = state.transportParams.maxStreamData
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
        stream.sendBuffer.addAll(data)
        stream.sendOffset += data.size.toULong()

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
        state.nextPacketNumber++
        state.bytesInFlight += data.size.toULong()

        packet
    }

    /** Create a new stream and return its ID */
    fun createStream(): ULong {
        val newStreamId = state.nextStreamId
        state.nextStreamId += 1uL
        return newStreamId
    }

    /** Get stream state */
    fun getStream(streamId: ULong): QuicStreamState? = streamStates[streamId]?.copy()

    /** Get active stream IDs */
    fun getActiveStreams(): List<ULong> = streamStates.keys.toList()

    /** Get connection state */
    fun getState(): QuicConnectionState = state.copy()

    /** Set connection state */
    fun setConnectionState(newState: ConnectionState) {
        state.connectionState = newState
    }

    /** Close the engine */
    suspend fun close() {
        // Cleanup
    }

    // ---- Private helpers ----

    private fun processStreamFrame(frame: StreamFrame) {
        val stream = streamStates.getOrPut(frame.streamId) {
            QuicStreamState(
                streamId = frame.streamId,
                maxData = state.transportParams.maxStreamData
            )
        }

        // Update stream receive buffer
        stream.receiveBuffer.addAll(frame.data)
        stream.receiveOffset = frame.offset + frame.data.size.toULong()

        // Mark packet for ACK
        ackPending.add(state.nextPacketNumber - 1uL)
    }

    private fun processAckFrame(frame: AckFrame) {
        // Remove acknowledged packets from bytes in flight
        var ackedBytes = 0uL
        for ((start, end) in frame.ackRanges) {
            ackedBytes += (end - start + 1uL) * 1350uL  // Assume max packet size
        }
        state.bytesInFlight = state.bytesInFlight.coerceAtLeast(ackedBytes) - ackedBytes
    }

    private fun processCryptoFrame(frame: CryptoFrame) {
        // Process crypto data (simplified -- would involve TLS in real impl)
        ackPending.add(state.nextPacketNumber - 1uL)
    }

    private fun createAckPacket(): QuicPacket {
        val sortedAcks = ackPending.sorted()

        val ranges = mutableListOf<Pair<ULong, ULong>>()
        if (sortedAcks.isNotEmpty()) {
            var start = sortedAcks[0]
            var end = sortedAcks[0]

            for (i in 1 until sortedAcks.size) {
                if (sortedAcks[i] == end + 1uL) {
                    end = sortedAcks[i]
                } else {
                    ranges.add(start to end)
                    start = sortedAcks[i]
                    end = sortedAcks[i]
                }
            }
            ranges.add(start to end)
        }

        val largestAcknowledged = sortedAcks.lastOrNull() ?: 0uL

        val ackFrame = AckFrame(
            largestAcknowledged = largestAcknowledged,
            ackDelay = 0uL,
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
            payload = emptyList()
        )
    }
}
