package borg.trikeshed.htx

import borg.trikeshed.context.Drain
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.forEach
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.reactor.TlsApplicationProtocol
import borg.trikeshed.reactor.TlsCodecBackend
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsConnectionState
import borg.trikeshed.reactor.TlsEndpoint
import borg.trikeshed.reactor.TlsFlowStage
import borg.trikeshed.reactor.TlsFrames
import borg.trikeshed.reactor.openTlsElement
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias HtxDispatch = Join<Join<HtxExchangeState, HtxRequest>, CompletableDeferred<HtxExchangeResult>>

class HtxReactorElement(
    private val channelOperations: ChannelOperations,
    val tlsBackend: TlsCodecBackend? = null,
    private val tlsConfig: TlsConfig = TlsConfig(
        alpnProtocols = arrayOf(TlsApplicationProtocol.HTTP_1_1).toSeries(),
    ),
    parentJob: kotlinx.coroutines.Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
) : HtxRouteService, Drain {
    override val key get() = HtxRouteService.Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    private val requests = Channel<HtxDispatch>(16, onUndeliveredElement = { dispatch ->
        dispatch.b.completeExceptionally(CancellationException("HTX exchange was not dispatched"))
    })
    private var worker: Job? = null

    suspend fun open() {
        if (worker == null && !requests.isClosedForSend) {
            worker = CoroutineScope(currentCoroutineContext() + supervisor + this + channelOperations).launch {
                // Each exchange owns its connection, ring and TLS engine, so exchanges run side by side:
                // a slow upstream never holds the requests queued behind it past their callers' deadlines.
                for (dispatch in requests) launch {
                    try {
                        dispatch.b.complete(exchangeRequest(dispatch.a.a, dispatch.a.b))
                    } catch (failure: Throwable) {
                        dispatch.b.completeExceptionally(failure)
                    }
                }.invokeOnCompletion { failure ->
                    // Cancellation can prevent the child body (including its catch) from starting.
                    if (failure != null) dispatch.b.completeExceptionally(failure)
                }
            }.also { job ->
                job.invokeOnCompletion { failure ->
                    // Every admitted exchange needs a terminal reply, even if the owner stops
                    // before this worker first runs or while requests remain in its channel.
                    if (failure != null) requests.cancel(CancellationException("HTX reactor stopped", failure))
                }
            }
        }
    }

    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult {
        check(worker != null && !requests.isClosedForSend) { "HTX reactor is not accepting exchanges" }
        val completion = CompletableDeferred<HtxExchangeResult>()
        requests.send((state j request) j completion)
        val result = withContext(NonCancellable) { completion.await() }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun exchangeRequest(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult =
        try {
            val transportRequest = request.withTransportDefaults()
            val response = kotlinx.coroutines.withTimeout(transportRequest.timeoutMs) {
                when (transportRequest.target.transportProtocol) {
                    HtxTransportProtocol.HTTP -> exchangePlain(transportRequest)
                    HtxTransportProtocol.HTTPS -> exchangeTls(transportRequest)
                }
            }
            HtxExchangeResult(
                state.copy(
                    lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = transportRequest,
                    response = response,
                ),
                htxFrames(
                    HtxFrame(
                        exchangeOrdinal = state.exchangeOrdinal,
                        stage = HtxFlowStage.REQUEST,
                        request = transportRequest,
                    ),
                    HtxFrame(
                        exchangeOrdinal = state.exchangeOrdinal,
                        stage = HtxFlowStage.RESPONSE,
                        request = transportRequest,
                        response = response,
                    ),
                ),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val failure = t.message ?: "HTX reactor exchange failed"
            HtxExchangeResult(
                state.copy(
                    lifecycle = HtxExchangeLifecycle.FAILED,
                    request = request,
                    failure = failure,
                    cause = t,
                ),
                htxFrames(
                    HtxFrame(
                        exchangeOrdinal = state.exchangeOrdinal,
                        stage = HtxFlowStage.REQUEST,
                        request = request,
                    ),
                    HtxFrame(
                        exchangeOrdinal = state.exchangeOrdinal,
                        stage = HtxFlowStage.FAILURE,
                        request = request,
                        failure = failure,
                    ),
                ),
            )
        }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        requests.close()
        worker?.join()
        supervisor.complete()
        supervisor.join()
        ownedSupervisor?.drain()
    }

    suspend fun close() = drain()

    private suspend fun exchangePlain(request: HtxRequest): HtxResponse {
        val connection = openConnection(request)
        return try {
            writeAll(connection.handle, connection.fd, ByteSeries(request.renderWireRequest()), request.timeoutMs)
            // renderWireRequest() is head-only — the body must follow on the wire
            // or a POST with Content-Length hangs the server (and our read) forever.
            if (request.body.rem > 0) {
                writeAll(connection.handle, connection.fd, request.body, request.timeoutMs)
            }
            parseHtxResponse(readAll(connection.handle, connection.fd, request.timeoutMs, request.maxResponseBytes))
        } finally {
            closeConnection(connection)
        }
    }

    private suspend fun exchangeTls(request: HtxRequest): HtxResponse {
        val backend = requireNotNull(tlsBackend) {
            "HTX HTTPS exchange requires TlsCodecBackend in NioSupervisor."
        }
        val connection = openConnection(request)
        return try {
            val tls = openTlsElement(
                config = tlsConfig,
                backend = backend,
                parentJob = supervisor,
            )
            // An IPv6 literal names the TLS peer by its address, without the URL's brackets.
            val endpoint = tls.clientEndpoint(request.target.host.removeSurrounding("[", "]"), request.target.port)
            try {
                performTlsHandshake(connection.handle, connection.fd, endpoint, request.timeoutMs)
                // renderWireRequest() is head-only — append the body bytes to
                // the TLS plaintext or a POST with Content-Length hangs the
                // server (and our read) forever.
                val headBytes = request.renderWireRequest().encodeToByteArray()
                val wireBytes: ByteArray = if (request.body.rem > 0) {
                    headBytes + request.body.toArray()
                } else {
                    headBytes
                }
                flushTlsFrames(
                    connection.handle,
                    connection.fd,
                    endpoint.upstream(ByteSeries(wireBytes)),
                    request.timeoutMs,
                )
                parseHtxResponse(
                    readTlsPlaintext(connection.handle, connection.fd, endpoint, request.timeoutMs, request.maxResponseBytes),
                )
            } finally {
                runCatching { flushTlsFrames(connection.handle, connection.fd, endpoint.close(), request.timeoutMs) }
                tls.drain()
            }
        } finally {
            closeConnection(connection)
        }
    }

    /** CLOSE is an SQE like everything else: prep on the connection's ring, settle, drop the ring. */
    private fun closeConnection(connection: HtxConnection) {
        try {
            check(connection.handle.prepClose(connection.fd) >= 0) { "HTX CLOSE SQE was rejected" }
            check(connection.handle.submit() == 1) { "HTX CLOSE SQE was not submitted" }
            val result = connection.handle.wait(1).single().res
            check(result == 0) { "HTX reactor close failed for fd=${connection.fd}: $result" }
        } finally {
            connection.handle.close()
        }
    }

    /** Encoded sockaddr for a CONNECT SQE, IPv4 when [host] resolves to one, else IPv6; null when DNS resolution fails. */
    private fun connectAddress(ops: ChannelOperations, host: String, port: Int): ByteBuffer? =
        ops.resolve(host.removeSurrounding("[", "]"))?.let { octets ->
            ByteBuffer(if (octets.size == 16) sockaddrIpv6(octets, port) else sockaddrIpv4(octets, port))
        }

    private fun openConnection(request: HtxRequest): HtxConnection {
        val ops = channelOperations
        val address = requireNotNull(connectAddress(ops, request.target.host, request.target.port)) {
            "HTX reactor could not resolve ${request.target.host} for CONNECT"
        }
        val handle = ops.openChannel()
        var fd = -1
        try {
            // Non-blocking, as the EAGAIN loops below read and write it; close-on-exec, as a supervised program holds none.
            check(handle.prepSocket(sockaddrFamily(address.array()), SocketType.withFlags(SocketType.SOCK_STREAM, SocketType.SOCK_NONBLOCK,
                SocketType.SOCK_CLOEXEC), SocketProtocol.IPPROTO_TCP.posix) >= 0)
            check(handle.submit() == 1) { "HTX SOCKET SQE was not submitted" }
            fd = handle.wait(1).single().res
            check(fd >= 0) { "HTX reactor socket failed: $fd" }
            check(handle.prepConnect(fd, address) >= 0)
            check(handle.submit() == 1) { "HTX CONNECT SQE was not submitted" }
            val connect = handle.wait(1).single().res
            check(connect == 0) {
                "HTX reactor connect SQE failed for ${request.target.host}:${request.target.port}: $connect"
            }
            return HtxConnection(fd, handle)
        } catch (failure: Throwable) {
            try {
                if (fd >= 0) closeConnection(HtxConnection(fd, handle)) else handle.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private suspend fun writeAll(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        payload: ByteSeries,
        timeoutMs: Long,
    ) {
        val buffer = ByteBuffer(payload.toArray())
        val completed = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (buffer.hasRemaining()) {
                check(handle.writev(fd, buffer) >= 0) { "HTX WRITE SQE was rejected" }
                check(handle.submit() == 1) { "HTX WRITE SQE was not submitted" }
                val result = waitFor(handle, fd, timeoutMs)
                check(result > 0 || result == -11) { "HTX reactor write failed for fd=$fd: $result" }
                if (result == -11) {
                    kotlinx.coroutines.delay(10)
                }
            }
            true
        } ?: false
        check(completed) {
            "HTX reactor write timed out for fd=$fd with ${buffer.remaining()} bytes remaining"
        }
    }

    private suspend fun readAll(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        timeoutMs: Long,
        maxResponseBytes: Int,
    ): ByteSeries {
        val response = ResponseAccumulator(maxResponseBytes)
        while (true) {
            val chunk = readChunk(handle, fd, timeoutMs) ?: break
            response.append(chunk)
            if (response.isComplete()) break
        }
        return response.toByteSeries()
    }

    private suspend fun performTlsHandshake(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        endpoint: TlsEndpoint,
        timeoutMs: Long,
    ) {
        val hello = endpoint.handshake()
        flushTlsFrames(handle, fd, hello, timeoutMs)
        while (!endpoint.isHandshakeComplete) {
            val chunk = requireNotNull(readChunk(handle, fd, timeoutMs)) {
                "TLS handshake failed for ${endpoint.remoteHost}:${endpoint.remotePort}: remote peer closed the channel."
            }
            flushTlsFrames(handle, fd, endpoint.downstream(chunk), timeoutMs)
        }
    }

    private suspend fun readTlsPlaintext(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        endpoint: TlsEndpoint,
        timeoutMs: Long,
        maxResponseBytes: Int,
    ): ByteSeries {
        val plaintext = ResponseAccumulator(maxResponseBytes)
        while (true) {
            val chunk = readChunk(handle, fd, timeoutMs) ?: break
            val frames = endpoint.downstream(chunk)
            plaintext.append(extractPlaintext(frames))
            flushTlsFrames(handle, fd, frames, timeoutMs)
            // RFC 8446 6.1: nothing after the peer's close_notify is data, so a close-delimited body ends there.
            if (plaintext.isComplete() || endpoint.flowState.lifecycle == TlsConnectionState.CLOSED) break
        }
        return plaintext.toByteSeries()
    }

    /**
     * Growable byte accumulator with O(1) amortized append and cached header parse.
     * The previous ArrayList<Byte> shape re-boxed every byte and [isCompleteResponse]
     * re-materialized and re-scanned the WHOLE buffer after every 16 KiB chunk — O(n²)
     * per response, which throttled replication pages and starved multi-megabyte
     * `_cas` blob fetches into the 30 s read deadline.
     */
    private class ResponseAccumulator(private val maxBytes: Int) {
        private var buf = ByteArray(minOf(32 * 1024, maxBytes))
        private var size = 0
        private var boundary = -1        // index of CRLFCRLF once seen
        private var contentLength = -2   // -2 unknown, -1 none declared
        private var chunks: HtxChunkFraming? = null

        fun append(chunk: ByteSeries) {
            val n = chunk.rem
            require(n <= maxBytes - size) { "HTX response byte limit exceeded" }
            if (size + n > buf.size) {
                var cap = buf.size
                while (cap < size + n) cap = minOf(maxBytes.toLong(), cap.toLong() * 2).toInt()
                buf = buf.copyOf(cap)
            }
            for (i in 0 until n) buf[size + i] = chunk[chunk.pos + i]
            size += n
            if (boundary < 0) scanBoundary()
        }

        private fun scanBoundary() {
            val start = maxOf(0, lastScan - 3)
            var i = start
            while (i <= size - 4) {
                if (buf[i] == CR && buf[i + 1] == LF && buf[i + 2] == CR && buf[i + 3] == LF) {
                    boundary = i
                    val headerText = buf.decodeToString(0, i)
                    contentLength = headerText.lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: -1
                    require(contentLength < 0 || contentLength <= maxBytes - boundary - 4) {
                        "HTX response byte limit exceeded"
                    }
                    if (headerText.lineSequence()
                        .any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }
                    ) chunks = HtxChunkFraming(boundary + 4)
                    return
                }
                i++
            }
            lastScan = size
        }

        private var lastScan = 0

        fun isComplete(): Boolean {
            if (boundary < 0) return false
            chunks?.let { return it.advance(buf, size) }
            if (contentLength >= 0) return size >= boundary + 4 + contentLength
            return false
        }

        fun toByteSeries(): ByteSeries = ByteSeries(buf.copyOf(size))

        companion object {
            private const val CR = '\r'.code.toByte()
            private const val LF = '\n'.code.toByte()
        }
    }

    private fun isCompleteResponse(sink: List<Byte>): Boolean {
        if (sink.size < 4) return false
        val bytes = ByteArray(sink.size) { sink[it] }
        val boundary = indexOfHeaderBoundary(bytes)
        if (boundary < 0) return false
        val headerText = bytes.decodeToString(0, boundary)
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull()
        if (contentLength != null) {
            return bytes.size >= boundary + 4 + contentLength
        }
        val isChunked = headerText.lineSequence()
            .any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }
        if (isChunked) {
            return HtxChunkFraming(boundary + 4).advance(bytes)
        }
        return false
    }

    private fun indexOfHeaderBoundary(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private suspend fun readChunk(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        timeoutMs: Long,
        // 16 KiB chunks made every response pay one readv/submit/waitFor round trip per 16 KiB;
        // with the flat 10 ms EAGAIN sleep below that capped the client near 1.6 MB/s and turned
        // replication pages and _cas blob fetches into multi-second exchanges.
        capacity: Int = 256 * 1024,
    ): ByteSeries? {
        val buffer = ByteBuffer(capacity)
        var idle = 0
        while (true) {
            check(handle.readv(fd, buffer) >= 0) { "HTX READ SQE was rejected" }
            check(handle.submit() == 1) { "HTX READ SQE was not submitted" }
            val result = waitFor(handle, fd, timeoutMs)
            check(result >= 0 || result == -11) { "HTX reactor read failed for fd=$fd: $result" }
            when {
                result == 0 -> return null
                result == -11 -> {
                    // Progressive backoff: on a live exchange the next bytes land within
                    // microseconds; sleep only when the peer is genuinely quiet.
                    when {
                        idle < 3 -> kotlinx.coroutines.yield()
                        idle < 20 -> kotlinx.coroutines.delay(1)
                        else -> kotlinx.coroutines.delay(10)
                    }
                    idle++
                    continue
                }
                result > 0 -> return ByteSeries(buffer.array().copyOf(result))
            }
        }
    }

    private suspend fun flushTlsFrames(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        frames: TlsFrames,
        timeoutMs: Long,
    ) {
        frames.forEach { frame ->
            when (frame.stage) {
                TlsFlowStage.UPSTREAM_CIPHERTEXT,
                TlsFlowStage.CLOSE_NOTIFY -> {
                    if (frame.payload.toArray().isNotEmpty()) {
                        writeAll(handle, fd, frame.payload.clone(), timeoutMs)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun extractPlaintext(frames: TlsFrames): ByteSeries {
        val plaintext = ArrayList<Byte>()
        frames.toList()
            .filter { it.stage == TlsFlowStage.DOWNSTREAM_PLAINTEXT }
            .forEach { appendBytes(plaintext, it.payload) }
        return plaintext.toByteSeries()
    }

    private suspend fun waitFor(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        timeoutMs: Long,
    ): Int {
        return kotlinx.coroutines.withTimeout(timeoutMs) {
            while (true) {
                val match = handle.wait(minComplete = 0).firstOrNull { it.fd == fd }
                if (match != null) {
                    return@withTimeout match.res
                }
                kotlinx.coroutines.delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private fun appendBytes(
        sink: MutableList<Byte>,
        bytes: ByteSeries,
    ) {
        val array = bytes.toArray()
        for (byte in array) {
            sink.add(byte)
        }
    }

    private fun MutableList<Byte>.toByteSeries(): ByteSeries =
        ByteSeries(ByteArray(size) { this[it] })

    private data class HtxConnection(
        val fd: Int,
        val handle: ChannelOperations.ChannelHandle,
    )
}

suspend fun openHtxReactorElement(
    channelOperations: ChannelOperations,
    tlsBackend: TlsCodecBackend? = null,
    tlsConfig: TlsConfig = TlsConfig(
        alpnProtocols = arrayOf(TlsApplicationProtocol.HTTP_1_1).toSeries(),
    ),
    parentJob: kotlinx.coroutines.Job? = null,
): HtxReactorElement =
    HtxReactorElement(
        channelOperations = channelOperations,
        tlsBackend = tlsBackend,
        tlsConfig = tlsConfig,
        parentJob = parentJob ?: currentCoroutineContext()[Job],
    ).also { it.open() }

suspend fun openHtxReactorElement(
    nioSupervisor: NioSupervisor? = null,
    tlsConfig: TlsConfig = TlsConfig(
        alpnProtocols = arrayOf(TlsApplicationProtocol.HTTP_1_1).toSeries(),
    ),
    parentJob: kotlinx.coroutines.Job? = null,
): HtxReactorElement {
    val contextSupervisor = currentCoroutineContext()[NioSupervisor.Key]
    val activeSupervisor = nioSupervisor ?: contextSupervisor ?: NioSupervisor()
    val ownsSupervisor = nioSupervisor == null && contextSupervisor == null

    if (!activeSupervisor.opened.isCompleted && activeSupervisor.admission.isActive) {
        activeSupervisor.open()
    }

    val channelOperations = activeSupervisor.service<ChannelOperations>()
        ?: error("HtxReactorElement requires ChannelOperations in NioSupervisor.")

    return HtxReactorElement(
        channelOperations = channelOperations,
        tlsBackend = activeSupervisor.service<TlsCodecBackend>(),
        tlsConfig = tlsConfig,
        parentJob = parentJob ?: currentCoroutineContext()[Job],
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
    ).also { it.open() }
}
