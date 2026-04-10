package borg.literbike.ccek.quic

// ============================================================================
// QUIC Protocol Types -- ported from quic_protocol.rs
// RFC-TRACE discipline: Every wire-format stanza carries an RFC section anchor.
// ============================================================================

/** High-level protocol selection for endpoints */
enum class QuicProtocol {
    H3,         // HTTP/3 over QUIC
    Custom,     // Custom QUIC protocol
    H3Datagram  // H3 + DATAGRAM/MASQUE
}

/**
 * QUIC packet types (RFC 9000 Section 17).
 * Long header types: Initial=0x00, 0-RTT=0x01, Handshake=0x02, Retry=0x03
 * Short header: bit 7 = 0
 */
enum class QuicPacketType(val value: UByte) {
    Initial(0x00u),
    ZeroRtt(0x01u),
    Handshake(0x02u),
    Retry(0x03u),
    VersionNegotiation(0x04u),
    ShortHeader(0x40u);

    fun isLongHeader(): Boolean = this != ShortHeader

    companion object {
        fun fromByte(b: UByte): QuicPacketType? = entries.find { it.value == b }

        fun fromFirstByte(first: UByte): QuicPacketType {
            if (first and 0x80u == 0x00u) return ShortHeader
            val typeBits = ((first.toInt() shr 4) and 0x03).toUByte()
            return when (typeBits) {
                0x00u -> Initial
                0x01u -> ZeroRtt
                0x02u -> Handshake
                0x03u -> Retry
                else -> Initial
            }
        }
    }
}

/** QUIC frame types (RFC 9000 Section 19) */
enum class QuicFrameType(val value: UByte) {
    Padding(0x00u),
    Ping(0x01u),
    Ack(0x02u),
    ResetStream(0x04u),
    StopSending(0x05u),
    Crypto(0x06u),
    NewToken(0x07u),
    Stream(0x08u),
    MaxData(0x10u),
    MaxStreamData(0x11u),
    MaxStreams(0x12u),
    DataBlocked(0x14u),
    StreamDataBlocked(0x15u),
    StreamsBlocked(0x16u),
    NewConnectionId(0x18u),
    RetireConnectionId(0x19u),
    PathChallenge(0x1Au),
    PathResponse(0x1Bu),
    ConnectionClose(0x1Cu),
    HandshakeDone(0x1Eu);

    companion object {
        fun fromByte(b: UByte): QuicFrameType? = entries.find { it.value == b }
    }
}

/** Connection ID (RFC 9000 Section 5.1) */
data class ConnectionId(val bytes: List<UByte>) {
    constructor(bytes: ByteArray) : this(bytes.toList().map { it.toUByte() })

    val length: Int get() = bytes.size

    companion object {
        val EMPTY = ConnectionId(emptyList())

        fun random(length: Int = 8): ConnectionId {
            val bytes = MutableList(length) { (0..255).random().toUByte() }
            return ConnectionId(bytes)
        }
    }
}

/** QUIC packet header (RFC 9000 Section 17) */
data class QuicHeader(
    val type: QuicPacketType,
    val version: ULong,
    val destinationConnectionId: ConnectionId,
    val sourceConnectionId: ConnectionId,
    var packetNumber: ULong,
    val token: List<UByte>? = null
)

/** MaxStreamData frame (RFC 9000 Section 19.11) */
data class MaxStreamDataFrame(
    val streamId: ULong,
    val maximumStreamData: ULong
)

/** StreamDataBlocked frame (RFC 9000 Section 19.14) */
data class StreamDataBlockedFrame(
    val streamId: ULong,
    val streamDataLimit: ULong
)

/** Stream frame (RFC 9000 Section 19.8) */
data class StreamFrame(
    val streamId: ULong,
    val offset: ULong,
    val data: List<UByte>,
    val fin: Boolean
)

/** ACK frame (RFC 9000 Section 19.3) */
data class AckFrame(
    val largestAcknowledged: ULong,
    val ackDelay: ULong,
    val ackRanges: List<Pair<ULong, ULong>>
)

/** Crypto frame (RFC 9000 Section 19.6) */
data class CryptoFrame(
    val offset: ULong,
    val data: List<UByte>
)

/** QUIC frame sum type */
sealed class QuicFrame {
    data object Padding : QuicFrame()
    data object Ping : QuicFrame()
    data class Ack(val frame: AckFrame) : QuicFrame()
    data object ResetStream : QuicFrame()
    data object StopSending : QuicFrame()
    data class Crypto(val frame: CryptoFrame) : QuicFrame()
    data object NewToken : QuicFrame()
    data class Stream(val frame: StreamFrame) : QuicFrame()
    data object MaxData : QuicFrame()
    data class MaxStreamData(val frame: MaxStreamDataFrame) : QuicFrame()
    data object MaxStreams : QuicFrame()
    data object DataBlocked : QuicFrame()
    data class StreamDataBlocked(val frame: StreamDataBlockedFrame) : QuicFrame()
    data object StreamsBlocked : QuicFrame()
    data object NewConnectionId : QuicFrame()
    data object RetireConnectionId : QuicFrame()
    data object PathChallenge : QuicFrame()
    data object PathResponse : QuicFrame()
    data object ConnectionClose : QuicFrame()
    data object HandshakeDone : QuicFrame()
}

/** QUIC packet */
data class QuicPacket(
    val header: QuicHeader,
    val frames: List<QuicFrame>,
    val payload: List<UByte>
)

/** Wire-decoded packet plus metadata needed by the engine */
data class DecodedQuicPacket(
    val packet: QuicPacket,
    val encodedPacketNumberLen: Int
)

/** QUIC transport parameters (RFC 9000 Section 18) */
data class TransportParameters(
    val maxStreamData: ULong = 1_048_576uL,
    val maxData: ULong = 10_485_760uL,
    val maxBidiStreams: ULong = 100uL,
    val maxUniStreams: ULong = 100uL,
    val idleTimeout: ULong = 30_000uL,
    val maxPacketSize: ULong = 1350uL,
    val ackDelayExponent: UInt = 3u,
    val maxAckDelay: ULong = 25uL,
    val activeConnectionIdLimit: ULong = 4uL,
    val initialMaxData: ULong = 10_485_760uL,
    val initialMaxStreamDataBidiLocal: ULong = 1_048_576uL,
    val initialMaxStreamDataBidiRemote: ULong = 1_048_576uL,
    val initialMaxStreamDataUni: ULong = 1_048_576uL,
    val initialMaxStreamsBidi: ULong = 100uL,
    val initialMaxStreamsUni: ULong = 100uL
)

/** Connection state machine states */
enum class ConnectionState {
    Idle,
    Handshaking,
    Connected,
    Closing,
    Closed,
    Draining
}

/** Stream state (RFC 9000 Section 3) */
enum class StreamState {
    Idle,
    Open,
    HalfClosedLocal,
    HalfClosedRemote,
    Closed
}

/** Stream priority levels for scheduling */
enum class StreamPriority(val value: UByte) {
    Critical(3u),
    High(2u),
    Normal(1u),
    Background(0u);

    fun asUByte(): UByte = value
}

/** QUIC stream state tracking */
data class QuicStreamState(
    val streamId: ULong,
    val sendBuffer: MutableList<UByte> = mutableListOf(),
    val receiveBuffer: MutableList<UByte> = mutableListOf(),
    var sendOffset: ULong = 0uL,
    var receiveOffset: ULong = 0uL,
    val maxData: ULong,
    var state: StreamState = StreamState.Idle,
    var priority: StreamPriority = StreamPriority.Normal,
    var bytesSent: ULong = 0uL,
    var bytesReceived: ULong = 0uL,
    var lastActivity: Long? = null  // epoch millis
)

/** QUIC connection state container */
data class QuicConnectionState(
    val version: ULong = 1uL,
    val localConnectionId: ConnectionId = ConnectionId.random(8),
    val remoteConnectionId: ConnectionId = ConnectionId.EMPTY,
    var nextPacketNumber: ULong = 0uL,
    val sentPackets: MutableList<QuicPacket> = mutableListOf(),
    val receivedPackets: MutableList<QuicPacket> = mutableListOf(),
    var bytesInFlight: ULong = 0uL,
    val transportParams: TransportParameters = TransportParameters(),
    var connectionState: ConnectionState = ConnectionState.Idle,
    var nextStreamId: ULong = 0uL
)

// ============================================================================
// Variable-length integer encoding (RFC 9000 Section 16)
// ============================================================================

/** Encode a variable-length integer per RFC 9000 Section 16 */
fun writeVarint(value: ULong, buf: MutableList<UByte>): Result<Unit> = runCatching {
    when {
        value < 64uL -> {
            buf.add(value.toUByte())
        }
        value < 16384uL -> {
            buf.add((0x40u or (value shr 8).toUByte()))
            buf.add(value.toUByte())
        }
        value < 1_073_741_824uL -> {
            buf.add((0x80u or ((value shr 24) and 0xFFu).toUByte()))
            buf.add(((value shr 16) and 0xFFu).toUByte())
            buf.add(((value shr 8) and 0xFFu).toUByte())
            buf.add((value and 0xFFu).toUByte())
        }
        else -> {
            buf.add((0xC0u or ((value shr 56) and 0xFFu).toUByte()))
            buf.add(((value shr 48) and 0xFFu).toUByte())
            buf.add(((value shr 40) and 0xFFu).toUByte())
            buf.add(((value shr 32) and 0xFFu).toUByte())
            buf.add(((value shr 24) and 0xFFu).toUByte())
            buf.add(((value shr 16) and 0xFFu).toUByte())
            buf.add(((value shr 8) and 0xFFu).toUByte())
            buf.add((value and 0xFFu).toUByte())
        }
    }
}

/** Read a variable-length integer. Returns (value, bytesConsumed) or null on underflow */
fun readVarint(buf: List<UByte>, pos: Int): Pair<ULong, Int>? {
    if (pos >= buf.size) return null
    val prefix = (buf[pos].toInt() shr 6) and 0x03
    val byteLen = 1 shl prefix
    if (pos + byteLen > buf.size) return null

    var value: ULong = (buf[pos].toUInt() and 0x3Fu).toULong()
    for (i in 1 until byteLen) {
        value = (value shl 8) or buf[pos + i].toULong()
    }
    return value to byteLen
}

/** Validate a stream ID per RFC 9000 rules */
fun validateStreamId(streamId: ULong): Result<Unit> {
    if (streamId and 0x3uL != 0x0uL) {
        return Result.failure(IllegalArgumentException("Stream ID must be divisible by 4"))
    }
    return Result.success(Unit)
}

// ============================================================================
// Frame encoding helpers (ported from Rust encode_frames)
// ============================================================================

/** Encode a list of frames into wire-format bytes */
fun encodeFrames(frames: List<QuicFrame>): Result<List<UByte>> = runCatching {
    val buf = mutableListOf<UByte>()
    for (frame in frames) {
        encodeFrame(frame, buf)
    }
    buf
}

private fun encodeFrame(frame: QuicFrame, buf: MutableList<UByte>) {
    when (frame) {
        QuicFrame.Padding -> {
            buf.add(0x00u)
        }
        QuicFrame.Ping -> {
            buf.add(0x01u)
        }
        is QuicFrame.Ack -> {
            buf.add(0x02u)
            writeVarint(frame.frame.largestAcknowledged, buf)
            writeVarint(frame.frame.ackDelay, buf)
            writeVarint(frame.frame.ackRanges.size.toULong() - 1uL, buf)
            var prev = frame.frame.largestAcknowledged
            for ((start, end) in frame.frame.ackRanges) {
                writeVarint(prev - end, buf)
                writeVarint(end - start, buf)
                prev = if (start > 0uL) start - 1uL else 0uL
            }
        }
        is QuicFrame.Crypto -> {
            buf.add(0x06u)
            writeVarint(frame.frame.offset, buf)
            writeVarint(frame.frame.data.size.toULong(), buf)
            buf.addAll(frame.frame.data)
        }
        is QuicFrame.Stream -> {
            // RFC 9000 Section 19.8: STREAM frame type byte construction
            var frameType: UByte = 0x08u or 0x02u  // Always include LEN
            if (frame.frame.offset > 0uL) frameType = frameType or 0x04u
            if (frame.frame.fin) frameType = frameType or 0x01u
            buf.add(frameType)
            writeVarint(frame.frame.streamId, buf)
            if (frame.frame.offset > 0uL) writeVarint(frame.frame.offset, buf)
            writeVarint(frame.frame.data.size.toULong(), buf)
            buf.addAll(frame.frame.data)
        }
        is QuicFrame.MaxStreamData -> {
            buf.add(0x11u)
            writeVarint(frame.frame.streamId, buf)
            writeVarint(frame.frame.maximumStreamData, buf)
        }
        is QuicFrame.StreamDataBlocked -> {
            buf.add(0x15u)
            writeVarint(frame.frame.streamId, buf)
            writeVarint(frame.frame.streamDataLimit, buf)
        }
        else -> {
            // Stub for unimplemented frame types
            buf.add(0x00u)
        }
    }
}

// ============================================================================
// Packet serialization (ported from Rust serialize_packet)
// ============================================================================

/** Serialize a QuicPacket into wire-format bytes */
fun serializePacket(packet: QuicPacket): Result<List<UByte>> = runCatching {
    val buf = mutableListOf<UByte>()
    serializeHeader(packet.header, buf)
    val frames = encodeFrames(packet.frames).getOrThrow()
    buf.addAll(frames)
    buf
}

private fun serializeHeader(header: QuicHeader, buf: MutableList<UByte>) {
    val firstByte: UByte = when (header.type) {
        QuicPacketType.ShortHeader -> {
            // Short header: bit 7 = 0, bits 5-6 = 0x40 (fixed bit)
            0x40u
        }
        QuicPacketType.Initial -> {
            // Long header: bit 7 = 1, type bits 00
            0xC0u
        }
        QuicPacketType.ZeroRtt -> {
            0xD0u
        }
        QuicPacketType.Handshake -> {
            0xE0u
        }
        QuicPacketType.Retry -> {
            0xF0u
        }
        QuicPacketType.VersionNegotiation -> {
            0x80u
        }
    }
    buf.add(firstByte)

    if (header.type != QuicPacketType.ShortHeader) {
        // Version (4 bytes)
        buf.add(((header.version shr 24) and 0xFFuL).toUByte())
        buf.add(((header.version shr 16) and 0xFFuL).toUByte())
        buf.add(((header.version shr 8) and 0xFFuL).toUByte())
        buf.add((header.version and 0xFFuL).toUByte())

        // DCID length + bytes
        buf.add(header.destinationConnectionId.length.toUByte())
        buf.addAll(header.destinationConnectionId.bytes)

        // SCID length + bytes
        buf.add(header.sourceConnectionId.length.toUByte())
        buf.addAll(header.sourceConnectionId.bytes)

        if (header.type == QuicPacketType.Initial) {
            // Token (Initial packets only)
            val token = header.token ?: emptyList()
            writeVarint(token.size.toULong(), buf)
            buf.addAll(token)
        }

        // Payload length (frames + packet number)
        // For now, estimate based on packet number field
        val pnLen = inferPacketNumberLen(header.packetNumber)
        // We don't know exact payload size here without encoding frames first
        // This is a simplification; real impl would encode frames first
        writeVarint(pnLen.toULong() + 100uL, buf)  // placeholder
    }

    // Packet number (truncated)
    val pnLen = inferPacketNumberLen(header.packetNumber)
    val pnMask = (1UL shl (pnLen * 8)) - 1uL
    val truncatedPn = header.packetNumber and pnMask
    for (i in pnLen - 1 downTo 0) {
        buf.add(((truncatedPn shr (i * 8)) and 0xFFuL).toUByte())
    }
}

/** Infer truncated packet number length from value (RFC 9000 Section 17.1) */
fun inferPacketNumberLen(truncatedPacketNumber: ULong): Int {
    return when {
        truncatedPacketNumber <= 0xFFuL -> 1
        truncatedPacketNumber <= 0xFFFFuL -> 2
        truncatedPacketNumber <= 0xFFFFFFuL -> 3
        else -> 4
    }
}

// ============================================================================
// Packet deserialization (ported from Rust deserialize_*)
// ============================================================================

/** Deserialize a decoded packet from wire bytes with known DCID length */
fun deserializeDecodedPacketWithDcidLen(
    data: List<UByte>,
    dcidLen: Int
): Result<DecodedQuicPacket> = runCatching {
    if (data.isEmpty()) throw IllegalArgumentException("Empty packet data")

    val firstByte = data[0]
    val packetType = QuicPacketType.fromFirstByte(firstByte)

    var pos = 1
    var version: ULong = 1uL
    var dcid: ConnectionId = ConnectionId.EMPTY
    var scid: ConnectionId = ConnectionId.EMPTY
    var token: List<UByte>? = null

    if (packetType != QuicPacketType.ShortHeader) {
        // Long header
        if (pos + 4 > data.size) throw IllegalArgumentException("Packet too short for version")
        version = ((data[pos].toULong() shl 24) or
                   (data[pos + 1].toULong() shl 16) or
                   (data[pos + 2].toULong() shl 8) or
                   data[pos + 3].toULong())
        pos += 4

        val dcidLenByte = data[pos].toInt()
        pos += 1
        dcid = ConnectionId(data.subList(pos, pos + dcidLenByte))
        pos += dcidLenByte

        val scidLenByte = data[pos].toInt()
        pos += 1
        scid = ConnectionId(data.subList(pos, pos + scidLenByte))
        pos += scidLenByte

        if (packetType == QuicPacketType.Initial) {
            val (tokenLen, tokenVarLen) = readVarint(data, pos)
                ?: throw IllegalArgumentException("Cannot read token length")
            pos += tokenVarLen
            token = data.subList(pos, pos + tokenLen.toInt())
            pos += tokenLen.toInt()
        }
    } else {
        // Short header: use provided dcidLen
        dcid = ConnectionId(data.subList(pos, pos + dcidLen))
        pos += dcidLen
    }

    // Packet number
    val pnOffset = pos
    val truncatedPn = when {
        pnOffset + 4 <= data.size -> {
            ((data[pnOffset].toULong() shl 24) or
             (data[pnOffset + 1].toULong() shl 16) or
             (data[pnOffset + 2].toULong() shl 8) or
             data[pnOffset + 3].toULong())
        }
        pnOffset + 3 <= data.size -> {
            ((data[pnOffset].toULong() shl 16) or
             (data[pnOffset + 1].toULong() shl 8) or
             data[pnOffset + 2].toULong())
        }
        pnOffset + 2 <= data.size -> {
            ((data[pnOffset].toULong() shl 8) or data[pnOffset + 1].toULong())
        }
        pnOffset < data.size -> {
            data[pnOffset].toULong()
        }
        else -> 0uL
    }

    val pnLen = inferPacketNumberLen(truncatedPn)
    val payload = data.subList(pos + pnLen, data.size)

    val header = QuicHeader(
        type = packetType,
        version = version,
        destinationConnectionId = dcid,
        sourceConnectionId = scid,
        packetNumber = truncatedPn,
        token = token
    )

    DecodedQuicPacket(
        packet = QuicPacket(
            header = header,
            frames = emptyList(),  // Frame decoding would require crypto context
            payload = payload
        ),
        encodedPacketNumberLen = pnLen
    )
}
