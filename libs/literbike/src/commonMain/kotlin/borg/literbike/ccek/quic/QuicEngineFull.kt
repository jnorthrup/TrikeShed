package borg.literbike.ccek.quic

// ============================================================================
// QUIC Engine Full -- ported from quic_engine_full.rs
// Complete engine with state machine, stream management, ACK handling
// ============================================================================

/**
 * QUIC Engine role (client or server)
 */
enum class QuicRole {
    Client,
    Server
}

/**
 * QUIC Engine -- ported from quic_engine_full.rs.
 * Full-featured engine with stream management, ACK handling,
 * and packet processing.
 */
class QuicEngineFull(
    private val role: QuicRole,
    private val port: UShort = 443u,
    private val privateKey: List<UByte> = List(32) { 0u }
) {
    private var state = QuicConnectionState()
    private val streamStates = mutableMapOf<ULong, QuicStreamState>()
    private val packetBuffer = mutableListOf<QuicPacket>()
    private val ackPending = mutableListOf<ULong>()

    /** Process incoming packet and return response packets */
    fun processPacket(packet: QuicPacket): List<QuicPacket> {
        val responses = mutableListOf<QuicPacket>()

        // Process each frame
        for (frame in packet.frames) {
            when (frame) {
                is QuicFrame.Stream -> processStreamFrame(frame.frame)
                is QuicFrame.Ack -> processAckFrame(frame.frame)
                is QuicFrame.Crypto -> processCryptoFrame(frame.frame)
                is QuicFrame.Ping -> {
                    synchronized(ackPending) {
                        ackPending.add(packet.header.packetNumber)
                    }
                }
                else -> { /* ignore other frame types */ }
            }
        }

        // Update state with received packet
        synchronized(packetBuffer) {
            packetBuffer.add(packet)
        }
        state.nextPacketNumber++

        // Generate ACK if needed
        synchronized(ackPending) {
            if (ackPending.isNotEmpty()) {
                responses.add(createAckPacket())
                ackPending.clear()
            }
        }

        return responses
    }

    /** Send data on a stream */
    fun sendStreamData(streamId: ULong, data: List<UByte>): QuicPacket {
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

        return packet
    }

    /** Create new stream and return stream ID */
    fun createStream(): ULong {
        val nextId = streamStates.size.toULong() * 4uL  // Client-initiated bidi stream
        return nextId
    }

    /** Get stream state */
    fun getStream(streamId: ULong): QuicStreamState? = streamStates[streamId]

    /** Get active stream IDs */
    fun getActiveStreams(): List<ULong> = streamStates.keys.toList()

    /** Get connection state */
    fun getState(): QuicConnectionState = state.copy()

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
        synchronized(ackPending) {
            ackPending.add(state.nextPacketNumber - 1uL)
        }
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
        synchronized(ackPending) {
            ackPending.add(state.nextPacketNumber - 1uL)
        }
    }

    private fun createAckPacket(): QuicPacket {
        val sortedAcks = ackPending.sorted()

        // Create ACK ranges
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

/** Key pair for QUIC (simplified) */
data class KeyPair(
    val privateKey: List<UByte>,
    val publicKey: List<UByte>
)

/** Generate a key pair for QUIC (simplified) */
fun generateKeyPair(): KeyPair {
    return KeyPair(
        privateKey = List(32) { i -> (i * 7).toUByte() },
        publicKey = List(32) { i -> (i * 13).toUByte() }
    )
}
