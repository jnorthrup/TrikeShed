package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrFamily
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** One VM's TCP descriptors. The guest sees lease-local ids, never host descriptors. */
class PythonSocketElement(scope: CoroutineScope, private val limit: Int = 32) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PythonSocketElement> {
        const val MAX_BYTES = 65_536
        const val MAX_TIMEOUT_MS = 30_000L
    }
    override val key: CoroutineContext.Key<*> get() = Key

    class SocketError(val errno: Int) : Exception("socket errno $errno")
    private class Request<T>(val effect: suspend () -> T) {
        val reply = CompletableDeferred<Result<T>>()
        suspend fun execute(): Unit = withContext(NonCancellable) {
            // A SOCKET CQE transfers a descriptor even if its requester has just been cancelled.
            // Record that ownership before cancellation can leave this admitted request.
            reply.complete(runCatching { effect() })
            Unit
        }
        fun reject() { reply.complete(Result.failure(CancellationException("socket lease closed"))) }
    }

    init { require(limit > 0) }
    private val supervisor = SupervisorJob(requireNotNull(scope.coroutineContext[Job]) {
        "Python sockets require an owning Job"
    }.also { it.ensureActive() })
    private val owner = CoroutineScope(scope.coroutineContext + supervisor + this)
    private val input = kotlinx.coroutines.channels.Channel<Request<*>>(limit, onUndeliveredElement = { it.reject() })
    private val disposed = CompletableDeferred<Result<Unit>>()
    // The serialized lane has at most one original SQE. The second slot is reserved for its cancellation.
    private val ring = FunctionalUringFacade.create(owner, entries = 2)
    private val sockets = mutableMapOf<Int, Join<Int, Int>>() // family j fd; only the worker mutates it
    private class TlsSocket(val owner: TlsElement, val endpoint: TlsEndpoint, var plaintext: ByteArray = ByteArray(0))
    private val encrypted = mutableMapOf<Int, TlsSocket>()
    private var identity = 0
    private var token = 0L
    private val worker = owner.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (request in input) {
                request.execute()
                currentCoroutineContext().ensureActive()
            }
        } finally {
            input.close()
            while (true) (input.tryReceive().getOrNull() ?: break).reject()
            withContext(NonCancellable) {
                // Drain admitted effects before closing their descriptors. If the owner aborted,
                // the original ring first settles its cancellation CQEs; cleanup gets its own joined ring.
                disposed.complete(runCatching {
                    var failure: Throwable? = null
                    for (id in encrypted.keys.toList()) {
                        try { closeTls(id, supervisor.isActive) }
                        catch (closing: Throwable) { if (failure == null) failure = closing else failure.addSuppressed(closing) }
                    }
                    ring.drain()
                    coroutineScope {
                        val closing = FunctionalUringFacade.create(this, entries = 1)
                        try {
                            for ((id, socket) in sockets.toMap()) {
                                try {
                                    val result = closing.batchEnqueue(1 j { _: Int -> Submissions.close(socket.b, ++token) })[0]
                                    checked(result.res)
                                    sockets.remove(id)
                                } catch (closingFailure: Throwable) {
                                    if (failure == null) failure = closingFailure else failure.addSuppressed(closingFailure)
                                }
                            }
                            failure?.let { throw it }
                            Unit
                        } finally { closing.drain() }
                    }
                })
            }
        }
    }.also { it.invokeOnCompletion { supervisor.complete() } }

    private suspend fun <T> submit(effect: suspend () -> T): T {
        val request = Request(effect)
        input.send(request)
        return withContext(NonCancellable) { request.reply.await().getOrThrow() }
    }

    private fun descriptor(id: Int): Join<Int, Int> = sockets[id] ?: throw SocketError(9)
    private fun checked(result: Int): Int = result.also { if (it < 0) throw SocketError(-it) }

    /** A deadline cancels only this token, then joins its terminal CQE before any buffer can escape. */
    private suspend fun io(submission: UringSubmission, timeoutMs: Long = MAX_TIMEOUT_MS): Int = supervisorScope {
        val submitted = submission.copy(userData = ++token)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ring.batchEnqueue(1 j { _: Int -> submitted })[0].res
        }
        suspend fun cancel() = ring.batchEnqueue(1 j { _: Int ->
            UringSubmission(UringOp.ASYNC_CANCEL, -1, submitted.userData, 0, 0, userData = ++token)
        })[0].res
        try {
            val value = withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { pending.await() }
            if (value != null) value else {
                checked(cancel().let { if (it == -2) 0 else it })
                // A completion that won the race with cancellation remains a completed effect.
                pending.await().let { if (it == -125) throw SocketError(110) else it }
            }
        } finally {
            if (!pending.isCompleted) withContext(NonCancellable) {
                try { cancel() } finally { pending.join() }
            }
        }
    }

    suspend fun socket(family: Int): Int = submit {
        require(family == 2 || family == 10) { "only AF_INET and AF_INET6 TCP sockets are leased" }
        if (sockets.size >= limit) throw SocketError(24)
        val fd = checked(io(UringSubmission(UringOp.SOCKET, family, 0, 0, 1)))
        (++identity).also { sockets[it] = family j fd }
    }

    suspend fun connect(id: Int, address: ByteArray, timeoutMs: Long): Unit = submit {
        require(timeoutMs in 1..MAX_TIMEOUT_MS)
        val socket = descriptor(id)
        require(sockaddrFamily(address) == socket.a) { "socket address family mismatch" }
        try {
            checked(io(Submissions.connect(socket.b, address, 0), timeoutMs))
        } catch (failure: Throwable) {
            // A timed-out connect is not reusable: cancellation of the request does not close the OS socket.
            try { checked(io(Submissions.close(socket.b, 0))); sockets.remove(id) }
            catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        Unit
    }

    private suspend fun transfer(id: Int, buffer: ByteBuffer, writing: Boolean, timeoutMs: Long): Int {
        require(timeoutMs in 0..MAX_TIMEOUT_MS)
        val fd = descriptor(id).b
        val start = TimeSource.Monotonic.markNow()
        fun remaining() = (timeoutMs - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
        while (true) {
            val submission = if (writing) Submissions.write(fd, 0, buffer.remaining(), -1, 0)
                else Submissions.read(fd, 0, buffer.remaining(), -1, 0)
            val count = io(submission.copy(buffer = buffer), remaining())
            if (count != -11) return checked(count)
            if (timeoutMs == 0L) throw SocketError(11)
            val left = remaining()
            if (left == 0L) throw SocketError(110)
            checked(io(Submissions.pollAdd(fd, if (writing) UringOp.POLLOUT else UringOp.POLLIN, 0), left))
        }
    }

    suspend fun read(id: Int, count: Int, timeoutMs: Long): ByteArray = submit {
        require(count in 0..MAX_BYTES)
        val stream = encrypted[id]
        if (stream == null) readRaw(id, count, timeoutMs) else {
            require(timeoutMs in 1..MAX_TIMEOUT_MS) { "TLS reads require a blocking deadline" }
            if (count == 0) ByteArray(0) else try {
                val remaining = deadline(timeoutMs)
                while (stream.plaintext.isEmpty() && stream.endpoint.flowState.lifecycle != TlsConnectionState.CLOSED) {
                    val bytes = readRaw(id, MAX_BYTES / 2, remaining())
                    if (bytes.isEmpty()) throw SocketError(71) // EOF without authenticated close_notify is truncation.
                    tlsFrames(id, stream, stream.endpoint.downstream(ByteSeries(bytes)), remaining)
                }
                val end = minOf(count, stream.plaintext.size)
                stream.plaintext.copyOfRange(0, end).also { stream.plaintext = stream.plaintext.copyOfRange(end, stream.plaintext.size) }
            } catch (failure: Throwable) {
                // An idle read deadline does not corrupt the codec or its partial record buffer.
                if (failure !is SocketError || failure.errno != 110) abortTls(id, failure)
                throw failure
            }
        }
    }

    private suspend fun readRaw(id: Int, count: Int, timeoutMs: Long): ByteArray {
        val buffer = ByteBuffer(ByteArray(count))
        val countRead = transfer(id, buffer, false, timeoutMs)
        return buffer.array().copyOf(countRead)
    }

    suspend fun write(id: Int, bytes: ByteArray, timeoutMs: Long): Int {
        require(bytes.size <= MAX_BYTES)
        val buffer = ByteBuffer(bytes.copyOf())
        return submit {
            val stream = encrypted[id]
            if (stream == null) transfer(id, buffer, true, timeoutMs) else {
                require(timeoutMs in 1..MAX_TIMEOUT_MS) { "TLS writes require a blocking deadline" }
                try {
                    tlsFrames(id, stream, stream.endpoint.upstream(ByteSeries(buffer.array())), deadline(timeoutMs))
                    bytes.size
                } catch (failure: Throwable) {
                    // Ciphertext partly sent cannot be replayed as a new TLS record.
                    abortTls(id, failure)
                    throw failure
                }
            }
        }
    }

    /** Upgrade the existing owned descriptor; trust policy and codec are supplied by the host. */
    suspend fun tls(id: Int, hostname: String, port: Int, timeoutMs: Long,
                    config: TlsConfig, backend: TlsCodecBackend): TlsSession = submit {
        descriptor(id)
        require(id !in encrypted && hostname.isNotBlank() && port in 1..65535)
        require(timeoutMs in 1..MAX_TIMEOUT_MS && config.hostnameVerification) { "TLS peer verification is required" }
        val element = openTlsElement(config, backend, parentJob = supervisor)
        val stream = TlsSocket(element, element.clientEndpoint(hostname, port))
        encrypted[id] = stream
        try {
            val remaining = deadline(timeoutMs)
            tlsFrames(id, stream, stream.endpoint.handshake(), remaining)
            while (!stream.endpoint.isHandshakeComplete) {
                if (stream.endpoint.flowState.lifecycle in setOf(TlsConnectionState.CLOSED, TlsConnectionState.FAILED)) throw SocketError(71)
                val bytes = readRaw(id, MAX_BYTES / 2, remaining())
                if (bytes.isEmpty()) throw SocketError(71)
                tlsFrames(id, stream, stream.endpoint.downstream(ByteSeries(bytes)), remaining)
            }
            requireNotNull(stream.endpoint.session).also { check(it.verified) { "TLS peer was not verified" } }
        } catch (failure: Throwable) {
            abortTls(id, failure)
            throw failure
        }
    }

    private fun deadline(timeoutMs: Long): () -> Long {
        val started = TimeSource.Monotonic.markNow()
        return { (timeoutMs - started.elapsedNow().inWholeMilliseconds).also { if (it <= 0) throw SocketError(110) } }
    }

    private suspend fun tlsFrames(id: Int, stream: TlsSocket, frames: TlsFrames, remaining: () -> Long) {
        for (frame in frames.view) when (frame.stage) {
            TlsFlowStage.UPSTREAM_CIPHERTEXT, TlsFlowStage.CLOSE_NOTIFY -> {
                val buffer = ByteBuffer(frame.payload.toArray())
                try {
                    while (buffer.hasRemaining()) if (transfer(id, buffer, true, remaining()) == 0) throw SocketError(32)
                } catch (failure: Throwable) {
                    abortTls(id, failure)
                    throw failure
                }
            }
            TlsFlowStage.DOWNSTREAM_PLAINTEXT -> {
                require(stream.plaintext.size + frame.payload.size <= MAX_BYTES) { "TLS plaintext exceeds receive bound" }
                stream.plaintext += frame.payload.toArray()
            }
            else -> Unit
        }
    }

    private suspend fun closeTls(id: Int, notify: Boolean) {
        val stream = encrypted.remove(id) ?: return
        try {
            val frames = stream.endpoint.close()
            if (notify) tlsFrames(id, stream, frames, deadline(1_000))
        } finally { stream.owner.drain() }
    }

    private suspend fun abortTls(id: Int, failure: Throwable) {
        try { closeTls(id, false) } catch (closing: Throwable) { failure.addSuppressed(closing) }
        try { sockets[id]?.let { checked(io(Submissions.close(it.b, 0))); sockets.remove(id) } }
        catch (closing: Throwable) { failure.addSuppressed(closing) }
    }

    suspend fun name(id: Int, peer: Boolean): ByteArray = submit {
        val buffer = ByteBuffer(128)
        checked(io(Submissions.getsockname(descriptor(id).b, buffer, peer, 0)))
        buffer.array()
    }

    suspend fun shutdown(id: Int, how: Int): Unit = submit {
        require(how in 0..2)
        if (id in encrypted) throw SocketError(95) // TLS half-close is not a raw socket shutdown.
        checked(io(Submissions.shutdown(descriptor(id).b, how, 0)))
        Unit
    }

    suspend fun close(id: Int): Unit = submit {
        try { closeTls(id, true) }
        finally { sockets[id]?.let { checked(io(Submissions.close(it.b, 0))); sockets.remove(id) } }
        Unit
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.join()
        disposed.await().getOrThrow()
    }
}
