package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.lib.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

data class QuicConfig(
    val alpn: Series<String> = Join.emptySeriesOf(),
    val maxIdleTimeoutMs: Long = 30000,
    val maxUdpPayloadSize: Int = 1350,
    val initialVersion: QuicVersion = QuicVersions.VERSION_1,
)

// ── QUIC Connection ID (RFC 9000 §5.1) ──────────────────────────────────────────

/**
 * QUIC connection ID — opaque byte sequence identifying a connection.
 * RFC 9000 §5.1: 0–20 bytes; servers choose the final length.
 * Short-header packets (1-RTT) use the destination connection ID chosen by the receiver.
 */
class QuicConnectionId(val bytes: ByteArray) {
    init { require(bytes.size in 0..20) { "Connection ID must be 0-20 bytes, got ${bytes.size}" } }

    companion object {
        /** Generate a random connection ID of [len] bytes (default 8). */
        fun generate(len: Int = 8): QuicConnectionId {
            require(len in 0..20) { "Connection ID length must be 0-20, got $len" }
            return QuicConnectionId(Random.nextBytes(len))
        }
    }
}

// ── QUIC short-header framing (RFC 9000 §17.3) ──────────────────────────────────

/** First byte for a short-header QUIC packet with spin bit set (0x40). */
const val SHORT_HEADER_MASK: Byte = 0x40

/**
 * Framed QUIC short-header packet.
 *
 * Layout (bytes):
 *   [0]       header byte (0x40 = short header, spin=1)
 *   [1..n]    destination connection ID (variable length)
 *   [n+1..]   protected payload
 */
data class QuicShortFrame(
    val dstConnectionId: QuicConnectionId,
    val payload: ByteArray,
) {
    val packet: ByteArray by lazy {
        val idLen = dstConnectionId.bytes.size
        val frame = ByteArray(1 + idLen + payload.size)
        frame[0] = SHORT_HEADER_MASK
        dstConnectionId.bytes.copyInto(frame, 1)
        payload.copyInto(frame, 1 + idLen)
        frame
    }
}

/**
 * Channelized QUIC transport CCEK service (Design 4 — io_uring + XDP).
 *
 * Key invariants (pure protocol engineering, no AI/ML):
 * - Each QUIC stream maps to a Kotlin Channel<ByteArray> under structured concurrency
 * - io_uring ring fd for zero-copy async I/O (system liburing / JNI binding)
 * - XDP/eBPF for deterministic packet → per-core io_uring ring steering (hash-based, not ML)
 * - Cancellation is free: parent scope death cleans all stream channels automatically
 * - ioUringFd = -1 means epoll fallback mode
 */
data class QuicChannelService(
    val _streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
    val ioUringFd: Int = -1,          // -1 = epoll fallback
    val xdpProg: String? = null,      // XDP prog name for hardware packet steering, null = software only
    val connectionId: QuicConnectionId = QuicConnectionId.generate(),
) : StreamTransport {
    companion object Key : CoroutineContext.Key<QuicChannelService>
    override val key: CoroutineContext.Key<*> get() = Key

    val streams: Map<Int, StreamHandle> get() = _streams

    override suspend fun openStream(): StreamHandle {
        val id = _streams.keys.maxOrNull()?.plus(1) ?: 0
        val send = Channel<ByteArray>(Channel.BUFFERED)
        val recv = Channel<ByteArray>(Channel.BUFFERED)
        val handle = StreamHandle(id, send, recv)
        _streams[id] = handle
        return handle
    }

    override val activeStreams: Int get() = _streams.size

    /** Frame [payload] as a QUIC short-header packet using this service's connection ID. */
    fun frameShortHeader(payload: ByteArray): QuicShortFrame =
        QuicShortFrame(connectionId, payload)
}

/**
 * QUIC connection configuration — mirrors literbike `quic_config::QuicConfig`.
 *
 * All fields have sensible defaults that match the Rust reference implementation.
 */
data class QuicConfigV2(
    /** Application-Layer Protocol Negotiation tokens, e.g. `listOf("h3".encodeToByteArray())`. */
    val alpn: List<ByteArray> = listOf("h3".encodeToByteArray()),
    /** Maximum idle timeout in milliseconds before the connection is silently closed. */
    val maxIdleTimeoutMs: ULong = 30_000uL,
    /** Maximum UDP datagram payload size (path MTU minus IP/UDP headers). */
    val maxUdpPayloadSize: UInt = 1350u,
    /** Enable Generic Segmentation Offload when the platform supports it. */
    val enableGso: Boolean = true,
    /** Enable Explicit Congestion Notification (ECN) markings. */
    val enableEcn: Boolean = true,
) {
    companion object {
        /** Convenience factory — returns the default configuration. */
        fun default(): QuicConfigV2 = QuicConfigV2()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuicConfigV2) return false
        if (alpn.size != other.alpn.size) return false
        for (i in alpn.indices) {
            if (!alpn[i].contentEquals(other.alpn[i])) return false
        }
        return maxIdleTimeoutMs == other.maxIdleTimeoutMs &&
                maxUdpPayloadSize == other.maxUdpPayloadSize &&
                enableGso == other.enableGso &&
                enableEcn == other.enableEcn
    }

    override fun hashCode(): Int {
        var result = alpn.fold(1) { acc, bytes -> 31 * acc + bytes.contentHashCode() }
        result = 31 * result + maxIdleTimeoutMs.hashCode()
        result = 31 * result + maxUdpPayloadSize.hashCode()
        result = 31 * result + enableGso.hashCode()
        result = 31 * result + enableEcn.hashCode()
        return result
    }
}

/**
 * Root QUIC error — mirrors literbike `quic_error::QuicError`.
 *
 * Every error category from the Rust source is represented as a sealed sub-hierarchy
 * so that `when` exhaustiveness checking works in Kotlin.
 */
sealed class QuicErrorException(override val message: String, override val cause: Throwable? = null) : Exception(message, cause) {

    // -- Connection errors ---------------------------------------------------

    sealed class Connection(
        msg: String,
        cause: Throwable? = null
    ) : QuicErrorException(msg, cause) {

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
    ) : QuicErrorException(msg, cause) {

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
    ) : QuicErrorException(msg, cause) {

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
    ) : QuicErrorException(msg, cause) {

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
    ) : QuicErrorException(msg) {

        data object ConnectionBlocked : FlowControl("Connection-level flow control blocked by peer")

        class StreamBlocked(
            val streamId: ULong
        ) : FlowControl("Stream $streamId flow control blocked by peer")
    }

    // -- Congestion-control errors -------------------------------------------

    sealed class CongestionControl(
        msg: String
    ) : QuicErrorException(msg) {

        class CongestionWindowBlocked(
            val inFlight: ULong,
            val window: ULong
        ) : CongestionControl("Congestion window blocked: inFlight=$inFlight, window=$window")
    }
}

/**
 * QUIC version numbers (RFC 9000 §15).
 * VERSION_1 = 0x00000001 is the current standard.
 * Version negotiation uses 0x00000000 as a sentinel.
 */
typealias QuicVersion = UInt

object QuicVersions {
    const val NEGOTIATION: UInt = 0x0000_0000u
    const val VERSION_1: UInt   = 0x0000_0001u
    const val DRAFT_29: UInt    = 0xff00_001du
    const val DRAFT_27: UInt    = 0xff00_001bu
}

/**
 * QUIC long header packet types (RFC 9000 §17.2).
 * The two high bits of the first byte determine long vs short header.
 */
enum class QuicLongPacketType(val code: UByte) {
    INITIAL(0x00u),
    ZERO_RTT(0x01u),
    HANDSHAKE(0x02u),
    RETRY(0x03u),
}

/**
 * QUIC short header packet type (RFC 9000 §17.3).
 * Short header packets always have bit 7 clear.
 */
enum class QuicShortPacketType(val code: UByte) {
    ONE_RTT(0x40u),  // spin bit set
    ONE_RTT_NO_SPIN(0x00u),  // spin bit clear
}

// ── Variable-length integer encoding (RFC 9000 §16) ──────────────────────────

/**
 * QUIC variable-length integer encoding (RFC 9000 §16).
 * Encodes a 62-bit unsigned integer in 1, 2, 4, or 8 bytes.
 *
 * Two high bits of the first byte determine length:
 *   00 → 1 byte  (value fits in 6 bits,  0..63)
 *   01 → 2 bytes (value fits in 14 bits, 0..16383)
 *   10 → 4 bytes (value fits in 30 bits, 0..1073741823)
 *   11 → 8 bytes (value fits in 62 bits, 0..4611686018427387903)
 */
object QuicVarInt {
    const val MAX_VALUE: ULong = 0x3FFF_FFFF_FFFF_FFFFuL  // 2^62 - 1

    /** Return the number of bytes needed to encode [value]. */
    fun encodedLen(value: ULong): Int = when {
        value <= 63uL          -> 1
        value <= 16383uL       -> 2
        value <= 1073741823uL  -> 4
        else                   -> 8
    }

    /** Encode [value] into [dst] starting at [offset]. Returns number of bytes written. */
    fun encode(value: ULong, dst: ByteArray, offset: Int = 0): Int {
        require(value <= MAX_VALUE) { "QUIC varint overflow: $value > $MAX_VALUE" }
        return when {
            value <= 63uL -> {
                dst[offset] = value.toByte()
                1
            }
            value <= 16383uL -> {
                dst[offset]     = (value shr 8).toByte().let { (it.toInt() or 0x40).toByte() }
                dst[offset + 1] = value.toByte()
                2
            }
            value <= 1073741823uL -> {
                dst[offset]     = (value shr 24).toByte().let { (it.toInt() or 0x80).toByte() }
                dst[offset + 1] = (value shr 16).toByte()
                dst[offset + 2] = (value shr 8).toByte()
                dst[offset + 3] = value.toByte()
                4
            }
            else -> {
                dst[offset]     = (value shr 56).toByte().let { (it.toInt() or 0xC0).toByte() }
                dst[offset + 1] = (value shr 48).toByte()
                dst[offset + 2] = (value shr 40).toByte()
                dst[offset + 3] = (value shr 32).toByte()
                dst[offset + 4] = (value shr 24).toByte()
                dst[offset + 5] = (value shr 16).toByte()
                dst[offset + 6] = (value shr 8).toByte()
                dst[offset + 7] = value.toByte()
                8
            }
        }
    }

    /** Decode a QUIC varint from [src] at [offset]. Returns (value, bytesConsumed). */
    fun decode(src: ByteArray, offset: Int = 0): Join<ULong, Int> {
        val first = src[offset].toInt() and 0xFF
        val tag = first shr 6
        return when (tag) {
            0 -> (first.toULong() and 0x3Fu) j 1
            1 -> {
                val value = ((first and 0x3F) shl 8) or (src[offset + 1].toInt() and 0xFF)
                value.toULong() j 2
            }
            2 -> {
                var value = 0L
                repeat(4) { i -> value = (value shl 8) or (src[offset + i].toInt() and 0xFF).toLong() }
                (value and 0x3FFFFFFF).toULong() j 4
            }
            3 -> {
                var value = 0L
                repeat(8) { i -> value = (value shl 8) or (src[offset + i].toInt() and 0xFF).toLong() }
                (value and 0x3FFFFFFF_FFFFFFFFL).toULong() j 8
            }
            else -> error("unreachable")
        }
    }
}

// ── Packet Header sealed hierarchy (RFC 9000 §17) ─────────────────────────────

/**
 * QUIC packet header — sealed class hierarchy modeling RFC 9000 §17.
 * Long header (bit 7=1): used before 1-RTT keys are established.
 * Short header (bit 7=0): used after 1-RTT keys are negotiated.
 */
sealed class QuicPacketHeader(
    open val dstConnectionId: ByteArray,
) {
    /**
     * RFC 9000 §17.2 — Long Header Packet.
     * Present during connection establishment (Initial, Handshake, 0-RTT, Retry).
     */
    sealed class Long(
        val version: QuicVersion,
        override val dstConnectionId: ByteArray,
        val srcConnectionId: ByteArray,
    ) : QuicPacketHeader(dstConnectionId) {

        /** Client initial packet — carries CRYPTO frame with TLS ClientHello */
        class Initial(
            val token: ByteArray,
            val packetNumber: ULong,
            val payload: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)

        /** 0-RTT early data — resumption of a prior connection */
        class ZeroRtt(
            val packetNumber: ULong,
            val payload: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)

        /** Handshake packet — carries remaining TLS handshake messages */
        class Handshake(
            val packetNumber: ULong,
            val payload: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)

        /** Stateless retry — server responds to Initial with address validation */
        class Retry(
            val retryToken: ByteArray,
            val retryIntegrityTag: ByteArray,
            version: QuicVersion,
            dstConnectionId: ByteArray,
            srcConnectionId: ByteArray,
        ) : Long(version, dstConnectionId, srcConnectionId)
    }

    /**
     * RFC 9000 §17.3 — Short Header Packet.
     * Used after 1-RTT keys are negotiated. Protected payload.
     */
    data class Short(
        override val dstConnectionId: ByteArray,
        val spinBit: Boolean = false,
        val reservedBits: UByte = 0u,
        val keyPhase: Boolean = false,
        val packetNumberLength: UByte = 2u,
        val packetNumber: ULong,
        val protectedPayload: ByteArray,
    ) : QuicPacketHeader(dstConnectionId)

    // ── helpers ────────────────────────────────────────────────────────────

    /** The first byte encodes header form (bit 7) and fixed bit (bit 6). */
    val headerFormBit: Boolean get() = true  // long header
    val fixedBit: Boolean get() = true       // always 1 in QUIC v1
}

val QuicKey: AsyncContextKey<QuicElement> = QuicElement.Key

suspend fun openQuicElement(config: QuicConfig = QuicConfig()): QuicElement =
    QuicElement(config).also { it.open() }

class QuicElement(
    val config: QuicConfig = QuicConfig(),
    val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<QuicElement>()

    override val key: AsyncContextKey<QuicElement>
        get() = Key

    override suspend fun openStream(): StreamHandle {
        requireState(ElementState.OPEN)
        val streamId = (streams.keys.maxOrNull() ?: -1) + 1
        val streamHandle = StreamHandle(
            id = streamId,
            send = Channel(Channel.BUFFERED),
            recv = Channel(Channel.BUFFERED),
        )
        streams[streamId] = streamHandle
        return streamHandle
    }

    override val activeStreams: Int get() = streams.size

    suspend fun connect(host: String, port: Int): StreamHandle {
        requireState(ElementState.OPEN)
        return openStream()
    }
}
