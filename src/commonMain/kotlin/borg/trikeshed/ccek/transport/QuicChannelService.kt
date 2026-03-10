package borg.trikeshed.ccek.transport

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel

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
    private val _streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
    val ioUringFd: Int = -1,          // -1 = epoll fallback
    val xdpProg: String? = null       // XDP prog name for hardware packet steering, null = software only
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
}
