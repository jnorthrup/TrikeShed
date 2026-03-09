package borg.trikeshed.ccek.transport

import kotlin.coroutines.CoroutineContext

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
    val streams: Map<Int, StreamHandle> = emptyMap(),
    val ioUringFd: Int = -1,          // -1 = epoll fallback
    val xdpProg: String? = null       // XDP prog name for hardware packet steering, null = software only
) : StreamTransport {
    companion object Key : CoroutineContext.Key<QuicChannelService>
    override val key: CoroutineContext.Key<*> get() = Key
    override suspend fun openStream(): StreamHandle = TODO("QUIC stream factory")
    override val activeStreams: Int get() = streams.size
}
