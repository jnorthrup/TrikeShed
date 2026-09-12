package borg.trikeshed.torrent

import borg.trikeshed.lib.get
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordered peer bytes. An accepted socket may have no endpoint metadata from its backend. */
interface PeerStream {
    val address: PeerAddress?
    suspend fun read(maxBytes: Int): ByteArray
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

/** One token namespace and bounded common ring per outbound connection or listener. */
internal class TorrentSocketRing(scope: CoroutineScope) {
    private val owner = SupervisorJob(requireNotNull(scope.coroutineContext[Job]))
    private val context = scope.coroutineContext + Dispatchers.Default + owner
    private val ring: FunctionalUringFacade
    private val tokens = Mutex()
    private val termination = Mutex()
    private var token = 0L
    @kotlin.concurrent.Volatile var closed = false
        private set

    init {
        // Validation/backend loading may fail before a constructed owner can be returned to its caller.
        val backend = try {
            owner.ensureActive()
            openUserspaceChannelBackend(32).let { backend ->
                context[UringTrace]?.let { UringTraceBackend(backend, it) } ?: backend
            }
        } catch (failure: Throwable) { owner.complete(); throw failure }
        try { ring = FunctionalUringFacade.create(CoroutineScope(context), 32, backend) }
        catch (failure: Throwable) {
            try { backend.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            owner.complete()
            throw failure
        }
    }

    suspend fun execute(op: UringOp, fd: Int = -1, buffer: ByteBuffer? = null,
                        length: Int = buffer?.remaining() ?: 0, offset: Long = 0): Int {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val id = tokens.withLock { check(!closed) { "Torrent ring closed" }; ++token }
        val allocating = op == UringOp.SOCKET || op == UringOp.ACCEPT
        suspend fun submit(): Int {
            val result = ring.batchEnqueue(s_[UringSubmission(op, fd, 0, length, offset,
                userData = id, buffer = buffer)])
            check(result.size == 1 && result[0].userData == id) { "Torrent CQE correlation failed" }
            return result[0].res
        }
        // Preserve an allocation CQE: prompt cancellation must not hide a newly accepted descriptor.
        val result = if (allocating) withContext(NonCancellable) { submit() } else submit()
        if (!caller.isActive && allocating && result >= 0) {
            withContext(NonCancellable) {
                try { torrentResult(UringOp.CLOSE, execute(UringOp.CLOSE, result)) }
                catch (cleanup: Throwable) {
                    // Cancellation of the ring owner may have stopped admission already. Drain closes
                    // every backend-owned allocation before propagating the original cancellation.
                    if (!owner.isActive || closed) close() else throw cleanup
                }
            }
        }
        caller.ensureActive()
        return result
    }

    suspend fun close() = withContext(NonCancellable) {
        termination.withLock {
            if (closed) return@withLock
            try { ring.drain() }
            finally { owner.complete(); owner.join(); closed = true }
        }
    }
}

private fun torrentSockaddr(address: PeerAddress): ByteBuffer {
    require(address.port in 0..65535)
    val octets = address.host.split('.').map { part ->
        require(part.isNotEmpty() && part.all { it in '0'..'9' }) { "Torrent TCP requires a numeric IPv4 peer: ${address.host}" }
        requireNotNull(part.toIntOrNull()).also { require(it in 0..255) }.toByte()
    }
    require(octets.size == 4) { "Torrent TCP requires a numeric IPv4 peer: ${address.host}" }
    return ByteBuffer(sockaddrIpv4(octets.toByteArray(), address.port))
}

private fun torrentResult(op: UringOp, result: Int): Int {
    if (result < 0) throw UringIOException(op, result)
    return result
}

internal class TorrentTcpStream(
    override val address: PeerAddress?,
    private val ring: TorrentSocketRing,
    private val fd: Int,
    private val ownsRing: Boolean,
) : PeerStream {
    private val reads = Mutex()
    private val writes = Mutex()
    private val termination = Mutex()
    @kotlin.concurrent.Volatile private var closed = false

    override suspend fun read(maxBytes: Int): ByteArray = reads.withLock {
        require(maxBytes in 1..1048576)
        val buffer = ByteBuffer(minOf(maxBytes, 65536))
        while (true) {
            currentCoroutineContext().ensureActive()
            if (closed) return@withLock ByteArray(0)
            val count = ring.execute(UringOp.READ, fd, buffer, offset = -1)
            if (count == -11 || count == -4 || count == -115) { delay(2); continue }
            if (closed) return@withLock ByteArray(0)
            torrentResult(UringOp.READ, count)
            check(count <= buffer.capacity() && buffer.position() == count) { "Invalid torrent READ completion" }
            return@withLock buffer.array().copyOf(count)
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }

    override suspend fun write(bytes: ByteArray) = writes.withLock {
        require(bytes.size <= 2 * 1048576)
        check(!closed) { "Torrent stream closed" }
        val buffer = ByteBuffer(bytes)
        while (buffer.hasRemaining()) {
            currentCoroutineContext().ensureActive()
            check(!closed) { "Torrent stream closed" }
            val start = buffer.position()
            val count = ring.execute(UringOp.WRITE, fd, buffer, offset = -1)
            if (count == -11 || count == -4 || count == -115) { delay(2); continue }
            torrentResult(UringOp.WRITE, count)
            check(count > 0 && buffer.position() == start + count) { "Invalid torrent WRITE completion" }
        }
    }

    override suspend fun close() = withContext(NonCancellable) {
        termination.withLock {
            if (closed) return@withLock
            closed = true
            try { if (!ring.closed) torrentResult(UringOp.CLOSE, ring.execute(UringOp.CLOSE, fd)) }
            finally { if (ownsRing) ring.close() }
        }
    }

    companion object {
        suspend fun connect(scope: CoroutineScope, address: PeerAddress, timeoutMillis: Long): PeerStream {
            require(address.port in 1..65535)
            val sockaddr = torrentSockaddr(address)
            require(timeoutMillis in 1..300000)
            val ring = TorrentSocketRing(scope)
            var fd = -1
            try {
                fd = torrentResult(UringOp.SOCKET, ring.execute(UringOp.SOCKET, 2, length = 6, offset = 1))
                withTimeout(timeoutMillis) {
                    val connected = CompletableDeferred<Unit>()
                    // batchEnqueue keeps an admitted CONNECT borrowed until its CQE settles.
                    // Cancel/drain its ring concurrently on timeout; the persistent ring owner stays
                    // under the session, so successful setup does not retain a timeout child forever.
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() }
                        finally { if (!connected.isCompleted) ring.close() }
                    }
                    try {
                        while (true) {
                            val result = ring.execute(UringOp.CONNECT, fd, sockaddr)
                            if (result == 0) break
                            if (result != -11 && result != -115 && result != -114 && result != -4)
                                torrentResult(UringOp.CONNECT, result)
                            delay(2)
                        }
                        connected.complete(Unit)
                    } finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
                }
                return TorrentTcpStream(address, ring, fd, true)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (fd >= 0 && !ring.closed) runCatching { torrentResult(UringOp.CLOSE, ring.execute(UringOp.CLOSE, fd)) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                    try { ring.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }
}

internal class TorrentTcpListener private constructor(private val ring: TorrentSocketRing, private val fd: Int) {
    private val termination = Mutex()
    @kotlin.concurrent.Volatile private var closed = false
    suspend fun accept(): PeerStream {
        while (true) {
            currentCoroutineContext().ensureActive()
            check(!closed) { "Torrent listener closed" }
            val accepted = ring.execute(UringOp.ACCEPT, fd)
            if (accepted == -11 || accepted == -4) { delay(5); continue }
            return TorrentTcpStream(null, ring, torrentResult(UringOp.ACCEPT, accepted), false)
        }
    }
    suspend fun close() = withContext(NonCancellable) {
        termination.withLock {
            if (!closed) {
                closed = true
                try { if (!ring.closed) torrentResult(UringOp.CLOSE, ring.execute(UringOp.CLOSE, fd)) }
                finally { ring.close() }
            }
        }
    }
    companion object {
        suspend fun bind(scope: CoroutineScope, address: PeerAddress): TorrentTcpListener {
            require(address.port in 1..65535) { "A concrete listen port is required" }
            val sockaddr = torrentSockaddr(address)
            val ring = TorrentSocketRing(scope)
            var fd = -1
            try {
                fd = torrentResult(UringOp.SOCKET, ring.execute(UringOp.SOCKET, 2, length = 6, offset = 1))
                torrentResult(UringOp.BIND, ring.execute(UringOp.BIND, fd, sockaddr))
                torrentResult(UringOp.LISTEN, ring.execute(UringOp.LISTEN, fd, length = 32))
                return TorrentTcpListener(ring, fd)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (fd >= 0 && !ring.closed) runCatching { torrentResult(UringOp.CLOSE, ring.execute(UringOp.CLOSE, fd)) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                    try { ring.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }
}
