package borg.trikeshed.ccek.transport

import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel

// ── QUIC Connection ID (RFC 9000 §5.1) ──────────────────────────────────────────

/**
 * QUIC connection ID — opaque byte sequence identifying a connection.
 * RFC 9000 §5.1: 0–20 bytes; servers choose the final length.
 * Short-header packets (1-RTT) use the destination connection ID chosen by the receiver.
 */
@JvmInline
value class QuicConnectionId(val bytes: ByteArray) {
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

/** First byte for a short-header QUIC packet with spin bit set (0x40). */const val SHORT_HEADER_MASK: Byte = 0x40

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
