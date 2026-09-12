package borg.trikeshed.htx

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
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
import borg.trikeshed.reactor.TlsEndpoint
import borg.trikeshed.reactor.TlsFlowStage
import borg.trikeshed.reactor.TlsFrames
import borg.trikeshed.reactor.openTlsElement
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.SocketProtocol
import borg.trikeshed.userspace.nio.channels.SocketType
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias HtxDispatch = Join<Join<HtxExchangeState, HtxRequest>, CompletableDeferred<HtxExchangeResult>>

class HtxReactorElement(
    private val channelOperations: ChannelOperations,
    private val tlsBackend: TlsCodecBackend? = null,
    private val tlsConfig: TlsConfig = TlsConfig(
        alpnProtocols = arrayOf(TlsApplicationProtocol.HTTP_1_1).toSeries(),
    ),
    parentJob: kotlinx.coroutines.Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob), HtxRouteService {
    override val key get() = HtxRouteService.Key

    private val requests = Channel<HtxDispatch>(16)
    private var worker: Job? = null

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
            worker = CoroutineScope(currentCoroutineContext() + supervisor + this + channelOperations).launch {
                for (dispatch in requests) {
                    try {
                        dispatch.b.complete(exchangeRequest(dispatch.a.a, dispatch.a.b))
                    } catch (failure: Throwable) {
                        dispatch.b.completeExceptionally(failure)
                    }
                }
            }
        }
    }

    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult {
        check(this.state == ElementState.ACTIVE) { "HTX reactor is not accepting exchanges" }
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
            val response = kotlinx.coroutines.withTimeout(30_000L) {
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
        if (state == ElementState.CLOSED) return@withContext
        state = ElementState.DRAINING
        requests.close()
        worker?.join()
        supervisor.complete()
        supervisor.join()
        try {
            ownedSupervisor?.drain()
        } finally {
            state = ElementState.CLOSED
        }
    }

    override suspend fun close() = drain()

    private suspend fun exchangePlain(request: HtxRequest): HtxResponse {
        val connection = openConnection(request)
        return try {
            writeAll(connection.handle, connection.fd, ByteSeries(request.renderWireRequest()))
            // renderWireRequest() is head-only — the body must follow on the wire
            // or a POST with Content-Length hangs the server (and our read) forever.
            if (request.body.rem > 0) {
                writeAll(connection.handle, connection.fd, request.body)
            }
            parseHtxResponse(readAll(connection.handle, connection.fd))
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
            val endpoint = tls.clientEndpoint(request.target.host, request.target.port)
            try {
                performTlsHandshake(connection.handle, connection.fd, endpoint)
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
                )
                parseHtxResponse(
                    readTlsPlaintext(connection.handle, connection.fd, endpoint),
                )
            } finally {
                runCatching { flushTlsFrames(connection.handle, connection.fd, endpoint.close()) }
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

    /** Encoded IPv4 sockaddr for a CONNECT SQE; null when DNS resolution fails. */
    private fun connectAddress(ops: ChannelOperations, host: String, port: Int): ByteBuffer? =
        ops.resolve(host)?.let { octets ->
            ByteBuffer(borg.trikeshed.userspace.nio.channels.sockaddrIpv4(octets, port))
        }

    private fun openConnection(request: HtxRequest): HtxConnection {
        val ops = channelOperations
        val address = requireNotNull(connectAddress(ops, request.target.host, request.target.port)) {
            "HTX reactor could not resolve ${request.target.host} for CONNECT"
        }
        val handle = ops.openChannel()
        var fd = -1
        try {
            check(handle.prepSocket(SocketDomain.AF_INET.posix, SocketType.SOCK_STREAM.mask, SocketProtocol.IPPROTO_TCP.posix) >= 0)
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
    ) {
        val buffer = ByteBuffer(payload.toArray())
        val completed = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            while (buffer.hasRemaining()) {
                check(handle.writev(fd, buffer) >= 0) { "HTX WRITE SQE was rejected" }
                check(handle.submit() == 1) { "HTX WRITE SQE was not submitted" }
                val result = waitFor(handle, fd, 30, TimeUnit.SECONDS)
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
    ): ByteSeries {
        val response = ResponseAccumulator()
        while (true) {
            val chunk = readChunk(handle, fd) ?: break
            response.append(chunk)
            if (response.isComplete()) break
        }
        return response.toByteSeries()
    }

    private suspend fun performTlsHandshake(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        endpoint: TlsEndpoint,
    ) {
        val hello = endpoint.handshake()
        flushTlsFrames(handle, fd, hello)
        while (!endpoint.isHandshakeComplete) {
            val chunk = requireNotNull(readChunk(handle, fd)) {
                "TLS handshake failed for ${endpoint.remoteHost}:${endpoint.remotePort}: remote peer closed the channel."
            }
            flushTlsFrames(handle, fd, endpoint.downstream(chunk))
        }
    }

    private suspend fun readTlsPlaintext(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        endpoint: TlsEndpoint,
    ): ByteSeries {
        val plaintext = ResponseAccumulator()
        while (true) {
            val chunk = readChunk(handle, fd) ?: break
            val frames = endpoint.downstream(chunk)
            plaintext.append(extractPlaintext(frames))
            flushTlsFrames(handle, fd, frames)
            if (plaintext.isComplete()) break
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
    private class ResponseAccumulator {
        private var buf = ByteArray(32 * 1024)
        private var size = 0
        private var boundary = -1        // index of CRLFCRLF once seen
        private var contentLength = -2   // -2 unknown, -1 none declared
        private var chunked = false

        fun append(chunk: ByteSeries) {
            val n = chunk.rem
            if (size + n > buf.size) {
                var cap = buf.size
                while (cap < size + n) cap = cap shl 1
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
                    chunked = headerText.lineSequence()
                        .any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }
                    return
                }
                i++
            }
            lastScan = size
        }

        private var lastScan = 0

        fun isComplete(): Boolean {
            if (boundary < 0) return false
            if (contentLength >= 0) return size >= boundary + 4 + contentLength
            if (chunked) {
                // terminal chunk marker in the tail
                val from = maxOf(boundary + 4, size - 7)
                val text = buf.decodeToString(from, size)
                return text.contains("0\r\n\r\n") || text.endsWith("0\r\n")
            }
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
            val body = bytes.copyOfRange(boundary + 4, bytes.size)
            val text = body.decodeToString()
            return text.contains("0\r\n\r\n") || text.endsWith("0\r\n")
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
            val result = waitFor(handle, fd, 30, TimeUnit.SECONDS)
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
    ) {
        frames.forEach { frame ->
            when (frame.stage) {
                TlsFlowStage.UPSTREAM_CIPHERTEXT,
                TlsFlowStage.CLOSE_NOTIFY -> {
                    if (frame.payload.toArray().isNotEmpty()) {
                        writeAll(handle, fd, frame.payload.clone())
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
        timeout: Long,
        unit: TimeUnit,
    ): Int {
        val timeoutMs = when (unit) {
            TimeUnit.MILLISECONDS -> timeout
            TimeUnit.SECONDS -> timeout * 1000
        }
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

    if (activeSupervisor.state == ElementState.CREATED) {
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
