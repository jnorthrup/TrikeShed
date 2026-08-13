package borg.trikeshed.htx

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.ByteSeries
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

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
        }
    }

    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult =
        try {
            val transportRequest = request.withTransportDefaults()
            val response = when (transportRequest.target.transportProtocol) {
                HtxTransportProtocol.HTTP -> exchangePlain(transportRequest)
                HtxTransportProtocol.HTTPS -> exchangeTls(transportRequest)
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

    override suspend fun close() {
        if (ownedSupervisor != null && ownedSupervisor.state.isLessThan(ElementState.CLOSED)) {
            ownedSupervisor.close()
        }
        super.close()
    }

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
            channelOperations.close(connection.fd)
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
                try {
                    flushTlsFrames(connection.handle, connection.fd, endpoint.close())
                } finally {
                    tls.close()
                }
            }
        } finally {
            channelOperations.close(connection.fd)
        }
    }

    private fun openConnection(request: HtxRequest): HtxConnection {
        val fd = channelOperations.socket(
            SocketDomain.AF_INET.posix,
            SocketType.SOCK_STREAM.mask,
            SocketProtocol.IPPROTO_TCP.posix,
        )
        check(fd >= 0) {
            "HTX reactor could not allocate socket for ${request.target.host}:${request.target.port}"
        }
        val connect = channelOperations.connect(fd, request.target.host, request.target.port)
        check(connect >= 0) {
            channelOperations.close(fd)
            "HTX reactor connect failed for ${request.target.host}:${request.target.port}"
        }
        return HtxConnection(fd, channelOperations.openChannel())
    }

    private suspend fun writeAll(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        payload: ByteSeries,
    ) {
        val buffer = ByteBuffer(payload.toArray())
        val completed = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            while (buffer.hasRemaining()) {
                handle.writev(fd, buffer)
                handle.submit()
                val result = waitFor(handle, fd, 30, TimeUnit.SECONDS)
                check(result >= 0) { "HTX reactor write failed for fd=$fd" }
                if (result == 0) {
                    // Non-blocking connect/write readiness is still pending.
                    // Preserve the buffer position and retry without occupying
                    // the reactor thread.
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
        val response = ArrayList<Byte>()
        while (true) {
            val chunk = readChunk(handle, fd) ?: break
            appendBytes(response, chunk)
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
        val plaintext = ArrayList<Byte>()
        while (true) {
            val chunk = readChunk(handle, fd) ?: break
            val frames = endpoint.downstream(chunk)
            appendBytes(plaintext, extractPlaintext(frames))
            flushTlsFrames(handle, fd, frames)
        }
        return plaintext.toByteSeries()
    }

    private suspend fun readChunk(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        capacity: Int = 16 * 1024,
    ): ByteSeries? {
        val buffer = ByteBuffer(capacity)
        while (true) {
            handle.readv(fd, buffer)
            handle.submit()
            val result = waitFor(handle, fd, 30, TimeUnit.SECONDS)
            check(result >= -1) { "HTX reactor read failed for fd=$fd" }
            // Non-blocking read: 0 = EAGAIN (no data yet, keep waiting);
            // -1 = EOF (peer closed). Previously 0 was misread as EOF, killing
            // every TLS handshake before the server's ServerHello could arrive.
            when {
                result == -1 -> return null
                result == 0 -> {
                    kotlinx.coroutines.delay(10)
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
        frames.toList().forEach { frame ->
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
        parentJob = parentJob,
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
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
    ).also { it.open() }
}
