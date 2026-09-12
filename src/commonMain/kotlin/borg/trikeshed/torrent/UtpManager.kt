package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.monotonicNanoTime
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

private fun utpParent(scope: CoroutineScope, port: Int): Job {
    require(port in 0..65535)
    return requireNotNull(scope.coroutineContext[Job]) { "uTP requires an owning Job" }
}

/** Outbound numeric IPv4 uTP. Each peer owns a connected UDP descriptor and bounded common ring.
 * Inbound/shared UDP admission requires sender metadata absent from the current facade contract.
 * Port zero requests an ephemeral source port; this API does not claim to know its allocated value.
 */
class UtpManager(
    scope: CoroutineScope, private val bindPort: Int = 0, val limits: UtpLimits = UtpLimits(),
) : AsyncContextElement(parentJob = utpParent(scope, bindPort)) {
    companion object Key : CoroutineContext.Key<UtpManager>
    override val key: CoroutineContext.Key<*> get() = Key
    private val context = scope.coroutineContext + supervisor + this
    private val admission = Mutex()
    private val termination = Mutex()
    private val permits = Semaphore(limits.maxConnections)
    private val streams = mutableSetOf<UtpStream>()

    suspend fun start() = admission.withLock {
        supervisor.ensureActive()
        check(state < ElementState.DRAINING) { "uTP manager is draining" }
        state = ElementState.ACTIVE
    }

    suspend fun connect(address: PeerAddress, connId: Int = Random.nextInt(65536)): UtpStream = withTimeout(limits.connectTimeoutMillis) {
        val sockaddr = utpSockaddr(address)
        require(connId in 0..65535)
        start()
        permits.acquire()
        var stream: UtpStream? = null
        try {
            admission.withLock {
                check(state < ElementState.DRAINING) { "uTP manager is draining" }
                stream = UtpStream(address, CoroutineScope(context), limits, connId, sockaddr, bindPort) { ended ->
                    admission.withLock { streams.remove(ended) }
                    permits.release()
                }.also { streams.add(it) }
            }
            stream!!.establish()
            currentCoroutineContext().ensureActive()
            stream!!
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (stream == null) permits.release()
                else try { stream!!.reject(failure) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override suspend fun close() = withContext(NonCancellable) {
        termination.withLock {
            val active = admission.withLock {
                if (state == ElementState.CLOSED) return@withLock null
                state = ElementState.DRAINING
                streams.toList()
            } ?: return@withLock
            var failure: Throwable? = null
            // Admit every close before joining, so their bounded FIN deadlines run concurrently.
            for (stream in active) try { stream.beginClose() } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure!!.addSuppressed(cause)
            }
            for (stream in active) try { stream.awaitClose() } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure!!.addSuppressed(cause)
            }
            supervisor.complete(); supervisor.join(); state = ElementState.CLOSED
            failure?.let { throw it }
            Unit
        }
    }
    override suspend fun drain() = close()
}

/** PeerStream exposes payload only. A successful write means every admitted byte was acknowledged. */
class UtpStream internal constructor(
    override val address: PeerAddress,
    scope: CoroutineScope,
    private val limits: UtpLimits,
    connId: Int,
    private val sockaddr: ByteArray,
    private val bindPort: Int,
    private val released: suspend (UtpStream) -> Unit,
) : AsyncContextElement(parentJob = requireNotNull(scope.coroutineContext[Job])), PeerStream {
    companion object Key : CoroutineContext.Key<UtpStream>
    override val key: CoroutineContext.Key<*> get() = Key
    private val context = scope.coroutineContext + supervisor + this
    private val ring = try { TorrentSocketRing(CoroutineScope(context)) }
        catch (failure: Throwable) { supervisor.complete(); throw failure }
    private val protocol = UtpSocket(connId, limits = limits)
    private val commands = Channel<Command>(8)
    private val setup = Mutex()
    private val reads = Mutex()
    private val writes = Mutex()
    private val admission = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private val finished = CompletableDeferred<Unit>()
    private var fd = -1
    private var worker: Job? = null
    private var closing = false
    private var closeFailure: Throwable? = null

    private sealed interface Command {
        class Read(val maximum: Int, val reply: CompletableDeferred<ByteArray>) : Command
        class Write(val bytes: ByteArray, val reply: CompletableDeferred<Unit>) : Command
        data object Close : Command
    }

    internal suspend fun establish(): Unit = coroutineScope {
        val accepted = CompletableDeferred<Unit>()
        // A borrowed CONNECT must settle before its batch returns. Timeout drains this peer's ring
        // concurrently; successful streams retain only the manager's persistent ownership.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!accepted.isCompleted) ring.close() }
        }
        try { setup.withLock {
            check(!closing)
            try {
                // SOCK_NONBLOCK is explicit: polling must never strand SEND behind a blocking RECV.
                fd = utpResult(UringOp.SOCKET, ring.execute(UringOp.SOCKET, 2, length = 17, offset = (2 or 0x800).toLong()))
                if (bindPort != 0) utpResult(UringOp.BIND, ring.execute(UringOp.BIND, fd,
                    ByteBuffer(sockaddrIpv4(byteArrayOf(0, 0, 0, 0), bindPort))))
                val endpoint = ByteBuffer(sockaddr)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val result = ring.execute(UringOp.CONNECT, fd, endpoint)
                    if (result == 0 || result == -106) break
                    if (result != -11 && result != -4 && result != -115 && result != -114) utpResult(UringOp.CONNECT, result)
                    delay(limits.tickMillis)
                }
                state = ElementState.ACTIVE
                worker = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) { run() }
            } catch (failure: Throwable) {
                dispose(failure)
                throw failure
            }
        }
            connected.await()
            accepted.complete(Unit)
            Unit
        } finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
    }

    override suspend fun read(maxBytes: Int): ByteArray = reads.withLock {
        require(maxBytes in 1..1048576)
        if (finished.isCompleted) {
            closeFailure?.let { throw it }
            return@withLock byteArrayOf()
        }
        val reply = CompletableDeferred<ByteArray>()
        try {
            admission.withLock {
                if (closing) { reply.complete(byteArrayOf()); return@withLock }
                commands.send(Command.Read(maxBytes, reply))
            }
            reply.await()
        } catch (failure: Throwable) {
            reply.cancel()
            throw failure
        }
    }

    override suspend fun write(bytes: ByteArray) = writes.withLock {
        require(bytes.size <= 2 * 1048576)
        withTimeout(limits.writeTimeoutMillis) {
            var offset = 0
            try {
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + limits.sendBytes)
                    val reply = CompletableDeferred<Unit>()
                    admission.withLock {
                        check(!closing && !finished.isCompleted) { "uTP stream is draining" }
                        commands.send(Command.Write(bytes.copyOfRange(offset, end), reply))
                    }
                    try { reply.await() } catch (failure: Throwable) { reply.cancel(); throw failure }
                    offset = end
                }
            } catch (failure: Throwable) {
                // Cancellation stops new admission; the owner drains bytes already admitted, with a FIN deadline.
                withContext(NonCancellable) {
                    try { beginClose() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }

    internal suspend fun beginClose() {
        setup.withLock {
            admission.withLock {
                if (closing || finished.isCompleted) return
                closing = true; state = ElementState.DRAINING
                if (worker == null) dispose(null) else commands.send(Command.Close)
            }
        }
    }
    internal suspend fun awaitClose() = withContext(NonCancellable) {
        finished.await(); supervisor.join()
        closeFailure?.let { throw it }
        Unit
    }
    override suspend fun close() = withContext(NonCancellable) { beginClose(); awaitClose() }
    override suspend fun drain() = close()

    internal suspend fun reject(failure: Throwable) = withContext(NonCancellable) {
        setup.withLock {
            if (!finished.isCompleted) {
                closing = true
                if (worker == null) dispose(failure) else commands.close(failure)
            }
        }
        finished.await(); supervisor.join()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        var read: Command.Read? = null
        var write: Command.Write? = null
        var closeAt: Long? = null
        var wire: ByteArray? = null
        var failure: Throwable? = null
        val buffer = ByteBuffer(65507)
        try {
            protocol.connect()
            while (true) {
                currentCoroutineContext().ensureActive()
                // Each tick bounds receive/send work, so commands and retransmission cannot starve.
                for (i in 0 until 32) {
                    buffer.clear()
                    val count = ring.execute(UringOp.RECV, fd, buffer)
                    if (count == -11 || count == -4) break
                    utpResult(UringOp.RECV, count)
                    check(count <= buffer.capacity() && buffer.position() == count) { "Invalid uTP RECV completion" }
                    if (count > 0) decodeUtpPacket(buffer.array().copyOf(count))?.let(protocol::receive)
                }
                protocol.tick()
                for (i in 0 until 32) {
                    val bytes = wire ?: protocol.takePacket() ?: break
                    wire = bytes
                    val sent = ring.execute(UringOp.SEND, fd, ByteBuffer(bytes))
                    if (sent == -11 || sent == -4) break
                    utpResult(UringOp.SEND, sent)
                    check(sent == bytes.size) { "Partial uTP datagram SEND completion" }
                    wire = null
                }
                if (protocol.state == UtpState.ERROR) error(protocol.failure ?: "uTP failed")
                if (protocol.state == UtpState.CONNECTED) connected.complete(Unit)
                if (read?.reply?.isCancelled == true) read = null
                read?.let { request -> protocol.read(request.maximum)?.let { request.reply.complete(it); read = null } }
                if (write != null && protocol.sendIdle) { write!!.reply.complete(Unit); write = null }
                if (protocol.state == UtpState.CLOSED && wire == null) break
                if (closeAt?.let { monotonicNanoTime() >= it } == true) error("uTP FIN drain timed out")
                select<Unit> {
                    commands.onReceive { command ->
                        try { when (command) {
                            is Command.Read -> {
                                check(read == null || read!!.reply.isCancelled) { "uTP read already pending" }
                                read = command
                            }
                            is Command.Write -> {
                                check(write == null && protocol.sendIdle) { "Previous uTP write is draining" }
                                check(protocol.send(command.bytes) == command.bytes.size) { "uTP send admission mismatch" }
                                write = command
                            }
                            Command.Close -> {
                                closeAt = monotonicNanoTime() + limits.closeTimeoutMillis * 1_000_000
                                protocol.close()
                            }
                        } } catch (cause: Throwable) {
                            when (command) {
                                is Command.Read -> command.reply.completeExceptionally(cause)
                                is Command.Write -> command.reply.completeExceptionally(cause)
                                Command.Close -> Unit
                            }
                            throw cause
                        }
                    }
                    onTimeout(limits.tickMillis) { }
                }
            }
        } catch (cause: Throwable) { failure = cause }
        finally {
            val cause = failure ?: IllegalStateException("uTP stream closed")
            connected.completeExceptionally(cause)
            write?.reply?.completeExceptionally(cause)
            read?.reply?.let { if (failure == null) it.complete(byteArrayOf()) else it.completeExceptionally(cause) }
            commands.close(cause)
            while (true) when (val command = commands.tryReceive().getOrNull() ?: break) {
                is Command.Read -> if (failure == null) command.reply.complete(byteArrayOf()) else command.reply.completeExceptionally(cause)
                is Command.Write -> command.reply.completeExceptionally(cause)
                Command.Close -> Unit
            }
            dispose(failure)
        }
    }

    private suspend fun dispose(failure: Throwable?) = withContext(NonCancellable) {
        if (finished.isCompleted) return@withContext
        closeFailure = failure
        try {
            if (fd >= 0 && !ring.closed) utpResult(UringOp.CLOSE, ring.execute(UringOp.CLOSE, fd))
        } catch (cleanup: Throwable) {
            if (closeFailure == null) closeFailure = cleanup else closeFailure!!.addSuppressed(cleanup)
        } finally {
            try { ring.close() } catch (cleanup: Throwable) {
                if (closeFailure == null) closeFailure = cleanup else closeFailure!!.addSuppressed(cleanup)
            }
            fd = -1; state = ElementState.CLOSED
            try { released(this@UtpStream) } finally {
                supervisor.complete(); finished.complete(Unit)
            }
        }
    }
}

private fun utpSockaddr(address: PeerAddress): ByteArray {
    require(address.port in 1..65535)
    val parts = address.host.split('.')
    require(parts.size == 4) { "uTP requires a numeric IPv4 peer" }
    return sockaddrIpv4(ByteArray(4) { index ->
        val part = parts[index]
        require(part.isNotEmpty() && part.all { it in '0'..'9' }) { "uTP requires a numeric IPv4 peer" }
        requireNotNull(part.toIntOrNull()).also { require(it in 0..255) }.toByte()
    }, address.port)
}

private fun utpResult(op: UringOp, result: Int): Int {
    if (result < 0) throw UringIOException(op, result)
    return result
}
