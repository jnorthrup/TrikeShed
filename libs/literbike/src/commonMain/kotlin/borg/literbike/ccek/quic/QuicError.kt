package borg.literbike.ccek.quic

// ============================================================================
// QUIC Error Types -- ported from quic_error.rs
// ============================================================================

/** Top-level QUIC error hierarchy */
sealed class QuicError(override val message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionError(val error: QuicConnectionError) : QuicError(error.message)
    class StreamError(val error: QuicStreamError) : QuicError(error.message)
    class Protocol(val error: ProtocolError) : QuicError(error.message)
    class Transport(val error: TransportError) : QuicError(error.message)
    class FlowControl(val error: FlowControlError) : QuicError(error.message)
    class CongestionControl(val error: CongestionControlError) : QuicError(error.message)
    class Io(override val cause: java.io.IOException) : QuicError("IO error: ${cause.message}", cause)
}

/** Connection-level errors */
sealed class QuicConnectionError(override val message: String) : Exception(message) {
    data object NotConnected : QuicConnectionError("QUIC connection not established")
    data object ConnectionClosed : QuicConnectionError("QUIC connection already closed")
    data class FlowControlBlocked(val windowSize: ULong, val attempted: ULong) :
        QuicConnectionError("Connection flow control blocked: window=$windowSize, attempted=$attempted")
    data class HandshakeFailed(val reason: String?) :
        QuicConnectionError("QUIC handshake failed: $reason")
    data class InvalidState(val state: String) :
        QuicConnectionError("Invalid state: $state")
}

/** Stream-level errors */
sealed class QuicStreamError(override val message: String) : Exception(message) {
    data class StreamNotFound(val streamId: ULong) :
        QuicStreamError("Stream $streamId not found")
    data class StreamClosed(val streamId: ULong) :
        QuicStreamError("Stream $streamId is closed")
    data class FlowControlBlocked(val streamId: ULong, val windowId: ULong, val attempted: ULong) :
        QuicStreamError("Stream $streamId flow control blocked: window=$windowId, attempted=$attempted")
    data class InvalidStreamId(val streamId: ULong) :
        QuicStreamError("Invalid stream ID: $streamId")
    data object StreamLimitExceeded :
        QuicStreamError("Maximum number of streams exceeded")
}

/** Protocol-level errors */
sealed class ProtocolError(override val message: String) : Exception(message) {
    data class InvalidPacket(val detail: String) :
        ProtocolError("Invalid packet: $detail")
    data class VersionMismatch(val local: ULong, val remote: ULong) :
        ProtocolError("QUIC version mismatch: local=$local, remote=$remote")
    data class Crypto(val detail: String) :
        ProtocolError("Crypto error: $detail")
    data class InvalidStreamId(val streamId: ULong) :
        ProtocolError("Invalid stream ID: $streamId")
}

/** Transport-level errors */
sealed class TransportError(override val message: String) : Exception(message) {
    data class Network(val detail: String) :
        TransportError("Network error: $detail")
    data class PacketTooLarge(val size: Int, val mtu: Int) :
        TransportError("Packet size $size exceeds MTU $mtu")
}

/** Flow control errors */
sealed class FlowControlError(override val message: String) : Exception(message) {
    data object ConnectionBlocked :
        FlowControlError("Connection-level flow control blocked by peer")
    data class StreamBlocked(val streamId: ULong) :
        FlowControlError("Stream $streamId flow control blocked by peer")
}

/** Congestion control errors */
sealed class CongestionControlError(override val message: String) : Exception(message) {
    data class CongestionWindowBlocked(val inFlight: ULong, val window: ULong) :
        CongestionControlError("Congestion window blocked: inFlight=$inFlight, window=$window")
}
