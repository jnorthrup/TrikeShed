package borg.trikeshed.ipns

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.TlsCodecBackend
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.containment.ContainmentPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Fixed IP/TCP multiaddrs; DNS and other transports require their own explicit common resolver. */
data class Libp2pTcpAddress(val host: String, val port: Int, val domain: Int, val sockaddr: ByteArray) {
    companion object {
        fun decode(bytes: ByteArray, peerId: ByteArray): Libp2pTcpAddress {
            val parsed = IpnsDhtAddresses.decode(bytes)
            require(parsed.peerId == null || parsed.peerId.contentEquals(peerId)) { "Multiaddr peer ID differs" }
            val address = parsed.ip
            val port = parsed.port
            val family = if (address.size == 4) 2 else 10
            val sockaddr = ByteArray(if (family == 2) 16 else 28)
            sockaddr[0] = family.toByte(); sockaddr[2] = (port ushr 8).toByte(); sockaddr[3] = port.toByte()
            address.copyInto(sockaddr, if (family == 2) 4 else 8)
            val host = if (family == 2) address.joinToString(".") { (it.toInt() and 255).toString() }
            else (0 until 8).joinToString(":") { (((address[it * 2].toInt() and 255) shl 8) or (address[it * 2 + 1].toInt() and 255)).toString(16) }
            return Libp2pTcpAddress(host, port, family, sockaddr)
        }
    }
}

/** Each logical outbound stream owns a connection and an existing bounded, scoped uring dispatcher. */
class UringLibp2pDialer(
    private val tlsBackend: (ByteArray) -> TlsCodecBackend,
    parentJob: Job? = null,
    private val timeoutMillis: Long = 15000,
    maxConnections: Int = 8,
    private val ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    context: CoroutineContext = EmptyCoroutineContext,
) : AsyncContextElement(ElementState.CREATED, parentJob), Libp2pDialer {
    override val key get() = Libp2pDialer.Key
    private val scope = CoroutineScope(context + Dispatchers.Default + supervisor)
    private val permits = Semaphore(maxConnections)
    private val registry = Mutex()
    private val streams = mutableSetOf<Libp2pStream>()
    init { require(timeoutMillis in 1..120000 && maxConnections in 1..64) }

    override suspend fun open(peer: Libp2pPeer, protocol: String): Libp2pStream = withTimeout(timeoutMillis) {
        super.open()
        check(state.isLessThan(ElementState.DRAINING)) { "Libp2p dialer is draining" }
        require(peer.id.isNotEmpty() && peer.addresses.size in 1..64)
        permits.acquire()
        var connected: Libp2pStream? = null
        try {
            var last: Throwable? = null
            for (i in 0 until peer.addresses.size) {
                currentCoroutineContext().ensureActive()
                var candidate: Libp2pStream? = null
                try {
                    val address = Libp2pTcpAddress.decode(peer.addresses[i], peer.id)
                    val tcp = Libp2pTcpStream.connect(scope, address, timeoutMillis, ebpfPrograms)
                    candidate = tcp
                    Libp2pMultistream.select(tcp, "/tls/1.0.0")
                    val tls = Libp2pTlsStream.open(tcp, tlsBackend(peer.id), address.host, address.port)
                    candidate = tls
                    Libp2pMultistream.select(tls, "/yamux/1.0.0")
                    val stream = Libp2pYamuxStream(tls, timeoutMillis)
                    candidate = stream
                    Libp2pMultistream.select(stream, protocol)
                    connected = stream
                    break
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { runCatching { candidate?.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (failure is CancellationException) throw failure
                    last = failure
                }
            }
            val stream = connected ?: throw IllegalStateException(
                "No authenticated libp2p TCP address succeeded: " + generateSequence(last) { it.cause }
                    .take(8).joinToString(" <- ") { it.message.orEmpty().take(256) }, last,
            )
            val owned = object : Libp2pStream {
                private val closeMutex = Mutex()
                private var closed = false
                override suspend fun read(maxBytes: Int): ByteArray = withTimeout(timeoutMillis) { stream.read(maxBytes) }
                override suspend fun write(bytes: ByteArray) = withTimeout(timeoutMillis) { stream.write(bytes) }
                override suspend fun close() = closeMutex.withLock {
                    if (!closed) {
                        closed = true
                        try { withContext(NonCancellable) { stream.close() } }
                        finally { registry.withLock { streams.remove(this) }; permits.release() }
                    }
                }
            }
            registry.withLock {
                check(state.isLessThan(ElementState.DRAINING)) { "Libp2p dialer is draining" }
                streams.add(owned)
            }
            owned
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { connected?.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
            permits.release()
            throw failure
        }
    }

    override suspend fun close() {
        val active = registry.withLock { state = ElementState.DRAINING; streams.toList() }
        withContext(NonCancellable) { for (stream in active) runCatching { stream.close() } }
        super.close()
    }

    override suspend fun drain() = close()
}

internal class Libp2pTcpStream private constructor(
    private val channel: UringChannel,
    private val file: File,
    private val timeout: Long,
) : Libp2pStream {
    private val mutex = Mutex()
    private var token = 1L
    private var closed = false

    private suspend fun request(op: UringOp, buffer: ByteBuffer? = null): Int {
        val id = token++
        val sqe = UringSubmission(op, file.id, 0, buffer?.remaining() ?: 0, if (op == UringOp.READ || op == UringOp.WRITE) -1 else 0,
            userData = id, buffer = buffer)
        val result = channel.batchEnqueue(s_[sqe])
        check(result.size == 1 && result[0].userData == id) { "Libp2p completion identity mismatch" }
        return result[0].res
    }

    override suspend fun read(maxBytes: Int): ByteArray = withTimeout(timeout) {
        require(maxBytes in 1..1048576)
        mutex.withLock {
            check(!closed)
            val buffer = ByteBuffer.allocate(minOf(maxBytes, 65536))
            while (true) {
                val count = request(UringOp.READ, buffer)
                when {
                    count == -11 || count == -115 -> delay(1)
                    count < 0 -> throw UringIOException(UringOp.READ, count)
                    else -> {
                        check(count <= buffer.capacity() && buffer.position() == count) { "Invalid libp2p READ completion" }
                        return@withLock buffer.array().copyOf(count)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") byteArrayOf()
        }
    }

    override suspend fun write(bytes: ByteArray) = withTimeout(timeout) {
        require(bytes.size <= 1048576)
        mutex.withLock {
            check(!closed)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val start = buffer.position()
                val count = request(UringOp.WRITE, buffer)
                when {
                    count == -11 || count == -115 || count == 0 -> delay(1)
                    count < 0 -> throw UringIOException(UringOp.WRITE, count)
                    else -> check(buffer.position() == start + count) { "Invalid libp2p WRITE completion" }
                }
            }
        }
    }

    override suspend fun close() = mutex.withLock {
        if (!closed) {
            closed = true
            try {
                val result = request(UringOp.CLOSE)
                if (result < 0 && result != -9) throw UringIOException(UringOp.CLOSE, result)
            } catch (failure: Throwable) {
                // Parent cancellation can close facade admission before this externally allocated socket.
                runCatching { file.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } finally { channel.drain() }
        }
    }

    companion object {
        suspend fun connect(scope: CoroutineScope, address: Libp2pTcpAddress, timeout: Long, programs: List<UringEbpfProgram>): Libp2pTcpStream {
            val policy = ContainmentPolicy.MAXIMUM.let { it.copy(layer3Syscall = it.layer3Syscall.copy(allowedEgress = setOf(address.host))) }
            val channel = UringChannels.open(scope, 16, programs, policy)
            val file = try { UringChannels.socket(address.domain, 1, 6) }
            catch (failure: Throwable) { channel.drain(); throw failure }
            val stream = Libp2pTcpStream(channel, file, timeout)
            try {
                withTimeout(timeout) {
                    while (true) {
                        val count = stream.request(UringOp.CONNECT, ByteBuffer.wrap(address.sockaddr))
                        if (count == 0) break
                        if (count != -11 && count != -115 && count != -114) throw UringIOException(UringOp.CONNECT, count)
                        delay(1)
                    }
                }
                return stream
            } catch (failure: Throwable) {
                withContext(NonCancellable) { runCatching { stream.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
                throw failure
            }
        }
    }
}
