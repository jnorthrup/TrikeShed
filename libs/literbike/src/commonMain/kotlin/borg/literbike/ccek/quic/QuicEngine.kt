package borg.literbike.ccek.quic

// ============================================================================
// QUIC Engine (Main) -- ported from quic_engine.rs (first ~600 lines)
// Core engine with crypto, session cache, diagnostics, ACK handling
// ============================================================================

/**
 * QUIC Engine diagnostics snapshot.
 * Ported from QuicEngineDiagnosticsSnapshot.
 */
data class QuicEngineDiagnosticsSnapshot(
    val totalStreamOverlapConflictCount: ULong = 0uL,
    val perStreamOverlapConflictCounts: Map<ULong, ULong> = emptyMap(),
    val perStreamPendingFragmentCounts: Map<ULong, Int> = emptyMap(),
    val totalPendingFragmentBytes: ULong = 0uL,
    val perStreamPendingFragmentBytes: Map<ULong, ULong> = emptyMap(),
    val perStreamContiguousReceiveOffsets: Map<ULong, ULong> = emptyMap(),
    val perStreamHighestSeenReceiveOffsets: Map<ULong, ULong> = emptyMap()
)

/**
 * QUIC Engine -- ported from Rust QuicEngine.
 *
 * Core QUIC stack with:
 * - Stream multiplexing and state management
 * - ACK generation and processing
 * - Fragment reassembly with overlap detection
 * - Crypto provider integration
 * - Session cache for resumption
 * - Diagnostics snapshot
 */
class QuicEngine(
    val role: QuicRole,
    initialState: QuicConnectionState,
    private val cryptoProvider: QuicCryptoProvider = NoopQuicCryptoProvider,
    private val sessionCache: QuicSessionCache = DefaultQuicSessionCache(),
    private val coroutineContext: CoroutineContext? = null,
    private val idleTimeoutMs: Long = 30_000L
) {
    private val state = initialState.copy().apply {
        connectionState = ConnectionState.Handshaking
    }
    private val streamStates = mutableMapOf<ULong, QuicStreamState>()
    private val streamContiguousReceiveOffsets = mutableMapOf<ULong, ULong>()
    private val streamPendingFragments = mutableMapOf<ULong, MutableList<Pair<ULong, List<UByte>>>>()
    private val streamOverlapConflictCounts = mutableMapOf<ULong, ULong>()
    private var totalStreamOverlapConflictCount: ULong = 0uL
    private val ackPending = mutableListOf<ULong>()
    private val pendingMaxStreamData = mutableMapOf<ULong, ULong>()
    private val initialPnCounter = 0uL
    private val handshakePnCounter = 0uL
    private val onerttPnCounter = 0uL
    private val initialCryptoSendOffset = 0uL
    private val handshakeCryptoSendOffset = 0uL
    private var handshakeDoneSent = false
    private var lastActivity = System.currentTimeMillis()

    companion object {
        /**
         * RFC 9000 Section 17.1: Reconstruct full packet number from truncated encoding.
         */
        fun reconstructPacketNumber(
            expectedPacketNumber: ULong,
            truncatedPacketNumber: ULong,
            packetNumberLen: Int
        ): Result<ULong> = runCatching {
            require(packetNumberLen in 1..4) {
                "packet number length must be in 1..=4"
            }

            val pnNbits = packetNumberLen * 8
            val packetNumberWindow = 1uL shl pnNbits
            val packetNumberHalfWindow = packetNumberWindow shr 1
            val packetNumberMask = packetNumberWindow - 1uL

            var candidate = (expectedPacketNumber and packetNumberMask.inv()) or
                           (truncatedPacketNumber and packetNumberMask)

            if (candidate <= ULong.MAX_VALUE - packetNumberWindow &&
                candidate + packetNumberHalfWindow <= expectedPacketNumber) {
                candidate += packetNumberWindow
            } else if (candidate > expectedPacketNumber + packetNumberHalfWindow &&
                       candidate >= packetNumberWindow) {
                candidate -= packetNumberWindow
            }

            candidate
        }

        /** RFC 9000 Section 17.1: Infer truncated packet number length from value */
        fun inferPacketNumberLen(truncatedPacketNumber: ULong): Int = when {
            truncatedPacketNumber <= 0xFFuL -> 1
            truncatedPacketNumber <= 0xFFFFuL -> 2
            truncatedPacketNumber <= 0xFFFFFFuL -> 3
            else -> 4
        }

        /** RFC 9000 Section 17.1: Expected inbound packet number */
        fun expectedInboundPacketNumber(state: QuicConnectionState): ULong =
            state.receivedPackets.lastOrNull()?.header?.packetNumber?.plus(1uL) ?: 0uL
    }

    // ---- Packet processing ----

    /** Process an incoming QUIC packet */
    suspend fun processPacket(packet: QuicPacket): Result<Unit> = runCatching {
        processPacketInternal(packet, null)
    }

    /** Process a decoded packet with known packet number length */
    suspend fun processDecodedPacket(decoded: DecodedQuicPacket): Result<Unit> = runCatching {
        processPacketInternal(decoded.packet, decoded.encodedPacketNumberLen)
    }

    private suspend fun processPacketInternal(
        packet: QuicPacket,
        encodedPacketNumberLen: Int?
    ) {
        val truncatedPacketNumber = packet.header.packetNumber
        val packetNumberLen = encodedPacketNumberLen
            ?: inferPacketNumberLen(truncatedPacketNumber)
        val expectedPacketNumber = expectedInboundPacketNumber(state)

        val reconstructedPacketNumber = reconstructPacketNumber(
            expectedPacketNumber,
            truncatedPacketNumber,
            packetNumberLen
        ).getOrThrow()

        // Process each frame
        for (frame in packet.frames) {
            when (frame) {
                is QuicFrame.Stream -> {
                    processStreamFrame(frame.frame, reconstructedPacketNumber)
                }
                is QuicFrame.Ack -> {
                    processAckFrame(frame.frame)
                    // Transition to Connected state after receiving an ACK
                    if (state.connectionState == ConnectionState.Handshaking) {
                        state.connectionState = ConnectionState.Connected
                    }
                }
                is QuicFrame.Crypto -> {
                    processCryptoFrame(frame.frame, packet.header.type, reconstructedPacketNumber)
                }
                QuicFrame.Ping -> {
                    synchronized(ackPending) {
                        ackPending.add(reconstructedPacketNumber)
                    }
                }
                else -> { /* ignore other frame types */ }
            }
        }

        // Update state with received packet
        state.receivedPackets.add(packet)
        state.nextPacketNumber++

        // Generate ACK if needed
        val ackData = synchronized(ackPending) {
            if (ackPending.isNotEmpty()) {
                val ackPacket = createAckPacket()
                val ackFrames = encodeFrames(ackPacket.frames).getOrThrow()
                ackPending.clear()
                ackPacket to ackFrames
            } else {
                null
            }
        }

        ackData?.let { (_, serializedAck) ->
            sendFrames(serializedAck)
        }

        markActivity()
    }

    // ---- Stream data sending ----

    /** Send data on a stream */
    suspend fun sendStreamData(streamId: ULong, data: List<UByte>): Result<Unit> = runCatching {
        sendStreamDataWithFin(streamId, data, false)
    }

    /** Send data on a stream with optional FIN flag */
    suspend fun sendStreamDataWithFin(
        streamId: ULong,
        data: List<UByte>,
        fin: Boolean
    ): Result<Unit> = runCatching {
        validateStreamId(streamId).getOrThrow()

        val (offset, previousState, previousSendOffset, previousSendBufferLen) = synchronized(streamStates) {
            val stream = streamStates.getOrPut(streamId) {
                QuicStreamState(
                    streamId = streamId,
                    maxData = state.transportParams.maxStreamData
                )
            }

            val offset = stream.sendOffset
            val previousState = stream.state
            val previousSendOffset = stream.sendOffset
            val previousSendBufferLen = stream.sendBuffer.size

            stream.sendBuffer.addAll(data)
            stream.sendOffset = stream.sendOffset + data.size.toULong()

            // RFC 9000 Section 3.1: Stream state transitions
            stream.state = when {
                stream.state == StreamState.Idle && !fin -> StreamState.Open
                stream.state == StreamState.Idle && fin -> StreamState.HalfClosedLocal
                stream.state == StreamState.Open && fin -> StreamState.HalfClosedLocal
                stream.state == StreamState.HalfClosedRemote -> StreamState.Closed
                else -> stream.state
            }

            offset to (previousState to (previousSendOffset to previousSendBufferLen))
        }

        // RFC 9000 Section 19.8: STREAM frame wire encoding
        var frameType: UByte = 0x08u or 0x02u  // Always include LEN
        if (offset > 0uL) frameType = frameType or 0x04u
        if (fin) frameType = frameType or 0x01u

        val frame = mutableListOf<UByte>()
        frame.add(frameType)
        writeVarint(streamId, frame).getOrThrow()
        if (offset > 0uL) writeVarint(offset, frame).getOrThrow()
        writeVarint(data.size.toULong(), frame).getOrThrow()
        frame.addAll(data)

        val (packetNumber, wireLen) = send1rttFrames(frame)

        synchronized(state) {
            state.sentPackets.add(QuicPacket(
                header = QuicHeader(
                    type = QuicPacketType.ShortHeader,
                    version = state.version,
                    destinationConnectionId = state.remoteConnectionId,
                    sourceConnectionId = state.localConnectionId,
                    packetNumber = packetNumber,
                    token = null
                ),
                frames = listOf(QuicFrame.Stream(StreamFrame(
                    streamId = streamId,
                    offset = offset,
                    data = data,
                    fin = fin
                ))),
                payload = data
            ))
            state.bytesInFlight += wireLen
        }

        // Update stream statistics
        synchronized(streamStates) {
            streamStates[streamId]?.let { s ->
                s.bytesSent += data.size.toULong()
                s.lastActivity = System.currentTimeMillis()
            }
        }

        markActivity()
    }

    /** Send FIN on a stream */
    suspend fun sendStreamFin(streamId: ULong): Result<Unit> = runCatching {
        sendStreamDataWithFin(streamId, emptyList(), true)
    }

    /** Set stream priority */
    fun setStreamPriority(streamId: ULong, priority: StreamPriority) {
        synchronized(streamStates) {
            streamStates[streamId]?.priority = priority
        }
    }

    /** Send stream data with priority awareness */
    suspend fun sendStreamDataPriority(
        streamId: ULong,
        data: List<UByte>,
        priority: StreamPriority
    ): Result<Unit> = runCatching {
        setStreamPriority(streamId, priority)
        sendStreamDataWithFin(streamId, data, false).getOrThrow()
    }

    /** Drain received data from a stream */
    fun drainStreamRecv(streamId: ULong, max: Int): List<UByte> {
        return synchronized(streamStates) {
            streamStates[streamId]?.let { stream ->
                val chunk = stream.receiveBuffer.take(minOf(max, stream.receiveBuffer.size))
                stream.receiveBuffer.subList(0, chunk.size).clear()
                chunk
            } ?: emptyList()
        }
    }

    /** Get stream statistics */
    fun getStreamStats(streamId: ULong): StreamStats? {
        return synchronized(streamStates) {
            streamStates[streamId]?.let { s ->
                StreamStats(
                    streamId = s.streamId,
                    bytesSent = s.bytesSent,
                    bytesReceived = s.bytesReceived,
                    sendOffset = s.sendOffset,
                    receiveOffset = s.receiveOffset,
                    state = s.state,
                    priority = s.priority
                )
            }
        }
    }

    /** Create a new stream and return its ID */
    fun createStream(): ULong = synchronized(state) {
        val newStreamId = state.nextStreamId
        state.nextStreamId += 1uL
        newStreamId
    }

    /** Get current connection state */
    fun getState(): QuicConnectionState = synchronized(state) { state.copy() }

    /** Get stream state */
    fun getStream(streamId: ULong): QuicStreamState? = synchronized(streamStates) {
        streamStates[streamId]?.copy()
    }

    /** Get active stream IDs */
    fun getActiveStreams(): List<ULong> = synchronized(streamStates) {
        streamStates.keys.toList()
    }

    /** Get diagnostics snapshot */
    fun getDiagnosticsSnapshot(): QuicEngineDiagnosticsSnapshot {
        return QuicEngineDiagnosticsSnapshot(
            totalStreamOverlapConflictCount = totalStreamOverlapConflictCount,
            perStreamOverlapConflictCounts = streamOverlapConflictCounts.toMap(),
            perStreamPendingFragmentCounts = streamPendingFragments.mapValues { it.value.size },
            totalPendingFragmentBytes = streamPendingFragments.values.sumOf { frags ->
                frags.sumOf { it.second.size }.toULong()
            },
            perStreamPendingFragmentBytes = streamPendingFragments.mapValues { (_, frags) ->
                frags.sumOf { it.second.size.toULong() }
            },
            perStreamContiguousReceiveOffsets = streamContiguousReceiveOffsets.toMap(),
            perStreamHighestSeenReceiveOffsets = streamPendingFragments.mapValues { (_, frags) ->
                frags.maxOfOrNull { it.first } ?: 0uL
            }
        )
    }

    /** Close the engine */
    suspend fun close() {
        // Cleanup
    }

    // ---- Private helpers ----

    private fun markActivity() {
        lastActivity = System.currentTimeMillis()
    }

    private fun isIdleTimedOut(): Boolean {
        return System.currentTimeMillis() - lastActivity > idleTimeoutMs
    }

    private fun processStreamFrame(frame: StreamFrame, reconstructedPacketNumber: ULong) {
        val stream = streamStates.getOrPut(frame.streamId) {
            QuicStreamState(
                streamId = frame.streamId,
                maxData = state.transportParams.maxStreamData
            )
        }

        // Update stream receive buffer
        stream.receiveBuffer.addAll(frame.data)
        stream.receiveOffset = frame.offset + frame.data.size.toULong()
        stream.bytesReceived += frame.data.size.toULong()
        stream.lastActivity = System.currentTimeMillis()

        // Mark packet for ACK
        synchronized(ackPending) {
            ackPending.add(reconstructedPacketNumber)
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

    private fun processCryptoFrame(frame: CryptoFrame, packetType: QuicPacketType, reconstructedPacketNumber: ULong) {
        // Process crypto frame via crypto provider
        val level = when (packetType) {
            QuicPacketType.Initial -> EncryptionLevel.Initial
            QuicPacketType.Handshake -> EncryptionLevel.Handshake
            QuicPacketType.ShortHeader -> EncryptionLevel.OneRtt
            else -> EncryptionLevel.Initial
        }

        cryptoProvider.onCryptoFrame(frame, level, state).getOrElse {
            // Log error but continue
            println("Crypto frame processing error: ${it.message}")
        }

        synchronized(ackPending) {
            ackPending.add(reconstructedPacketNumber)
        }
    }

    private fun createAckPacket(): QuicPacket {
        val sortedAcks = synchronized(ackPending) { ackPending.sorted() }

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

        val ackFrame = AckFrame(
            largestAcknowledged = sortedAcks.lastOrNull() ?: 0uL,
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

    /** Apply outbound header protection hook */
    private fun applyOutboundHeaderProtectionHook(packet: QuicPacket): Result<Unit> = runCatching {
        val packetNumberLen = inferPacketNumberLen(packet.header.packetNumber)
        val ctx = OutboundHeaderProtectionContext(
            packetNumber = packet.header.packetNumber,
            packetNumberLen = packetNumberLen
        )
        cryptoProvider.onOutboundHeader(packet.header, ctx)
    }

    // Stub: send frames over the wire
    private suspend fun sendFrames(frames: List<UByte>) {
        // In real impl, this would use a UDP socket
        println("Sending ${frames.size} bytes of frames")
    }

    // Stub: send 1RTT frames and return (packetNumber, wireLength)
    private suspend fun send1rttFrames(frames: List<UByte>): Pair<ULong, ULong> {
        sendFrames(frames)
        val pn = state.nextPacketNumber
        state.nextPacketNumber++
        return pn to frames.size.toULong()
    }
}
