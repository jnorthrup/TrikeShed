package borg.trikeshed.ccek.transport.quic

/**
 * Root QUIC error — mirrors literbike `quic_error::QuicError`.
 *
 * Every error category from the Rust source is represented as a sealed sub-hierarchy
 * so that `when` exhaustiveness checking works in Kotlin.
 */
sealed class QuicError(override val message: String, override val cause: Throwable? = null) : Exception(message, cause) {

    // -- Connection errors ---------------------------------------------------

    sealed class Connection(
        msg: String,
        cause: Throwable? = null
    ) : QuicError(msg, cause) {

        data object NotConnected : Connection("QUIC connection not established")
        data object ConnectionClosed : Connection("QUIC connection already closed")

        class FlowControlBlocked(
            val windowSize: ULong,
            val attempted: ULong
        ) : Connection("Connection flow control blocked: window=$windowSize, attempted=$attempted")

        class HandshakeFailed(
            cause: Throwable? = null
        ) : Connection("QUIC handshake failed", cause)

        class InvalidState(
            val state: String
        ) : Connection("Invalid state: $state")
    }

    // -- Stream errors -------------------------------------------------------

    sealed class Stream(
        msg: String,
        cause: Throwable? = null
    ) : QuicError(msg, cause) {

        class StreamNotFound(
            val streamId: ULong
        ) : Stream("Stream $streamId not found")

        class StreamClosed(
            val streamId: ULong
        ) : Stream("Stream $streamId is closed")

        class FlowControlBlocked(
            val streamId: ULong,
            val windowId: ULong,
            val attempted: ULong
        ) : Stream("Stream $streamId flow control blocked: window=$windowId, attempted=$attempted")

        class InvalidStreamId(
            val streamId: ULong
        ) : Stream("Invalid stream ID: $streamId")

        data object StreamLimitExceeded : Stream("Maximum number of streams exceeded")
    }

    // -- Protocol errors -----------------------------------------------------

    sealed class Protocol(
        msg: String,
        cause: Throwable? = null
    ) : QuicError(msg, cause) {

        class InvalidPacket(
            val detail: String
        ) : Protocol("Invalid packet: $detail")

        class VersionMismatch(
            val local: ULong,
            val remote: ULong
        ) : Protocol("QUIC version mismatch: local=$local, remote=$remote")

        class Crypto(
            val detail: String,
            cause: Throwable? = null
        ) : Protocol("Crypto error: $detail", cause)

        class InvalidStreamId(
            val streamId: ULong
        ) : Protocol("Invalid stream ID: $streamId")
    }

    // -- Transport errors ----------------------------------------------------

    sealed class Transport(
        msg: String,
        cause: Throwable? = null
    ) : QuicError(msg, cause) {

        class Network(
            val detail: String,
            cause: Throwable? = null
        ) : Transport("Network error: $detail", cause)

        class PacketTooLarge(
            val size: Int,
            val mtu: Int
        ) : Transport("Packet size $size exceeds MTU $mtu")
    }

    // -- Flow-control errors -------------------------------------------------

    sealed class FlowControl(
        msg: String
    ) : QuicError(msg) {

        data object ConnectionBlocked : FlowControl("Connection-level flow control blocked by peer")

        class StreamBlocked(
            val streamId: ULong
        ) : FlowControl("Stream $streamId flow control blocked by peer")
    }

    // -- Congestion-control errors -------------------------------------------

    sealed class CongestionControl(
        msg: String
    ) : QuicError(msg) {

        class CongestionWindowBlocked(
            val inFlight: ULong,
            val window: ULong
        ) : CongestionControl("Congestion window blocked: inFlight=$inFlight, window=$window")
    }
}
