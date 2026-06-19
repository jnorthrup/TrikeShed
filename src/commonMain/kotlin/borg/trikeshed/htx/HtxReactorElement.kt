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
                flushTlsFrames(
                    connection.handle,
                    connection.fd,
                    endpoint.upstream(ByteSeries(request.renderWireRequest())),
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

    private fun writeAll(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        payload: ByteSeries,
    ) {
        val buffer = ByteBuffer(payload.toArray())
        while (buffer.hasRemaining()) {
            handle.writev(fd, buffer)
            handle.submit()
            val result = await(handle, fd)
            check(result >= 0) { "HTX reactor write failed for fd=$fd" }
            check(result != 0 || !buffer.hasRemaining()) { "HTX reactor write stalled for fd=$fd" }
        }
    }

    private fun readAll(
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
        flushTlsFrames(handle, fd, endpoint.handshake())
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

    private fun readChunk(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
        capacity: Int = 16 * 1024,
    ): ByteSeries? {
        val buffer = ByteBuffer(capacity)
        handle.readv(fd, buffer)
        handle.submit()
        val result = await(handle, fd)
        check(result >= -1) { "HTX reactor read failed for fd=$fd" }
        if (result <= 0) {
            return null
        }
        return ByteSeries(buffer.array().copyOf(result))
    }

    private fun flushTlsFrames(
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

    private fun await(
        handle: ChannelOperations.ChannelHandle,
        fd: Int,
    ): Int =
        handle.wait(minComplete = 1)
            .firstOrNull { it.fd == fd }
            ?.res
            ?: error("HTX reactor received no completion for fd=$fd")

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
