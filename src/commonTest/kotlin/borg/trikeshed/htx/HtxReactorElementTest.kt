package borg.trikeshed.htx

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.reactor.TlsChannelFrame
import borg.trikeshed.reactor.TlsCodecBackend
import borg.trikeshed.reactor.TlsCodecResult
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsConnectionState
import borg.trikeshed.reactor.TlsFlowStage
import borg.trikeshed.reactor.TlsFlowState
import borg.trikeshed.reactor.TlsPayload
import borg.trikeshed.reactor.TlsProtocol
import borg.trikeshed.reactor.TlsSession
import borg.trikeshed.reactor.tlsFrames
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtxReactorElementTest {
    @Test
    fun ccekHtxElementResolvesRegisteredPlainReactorRouteService() = runTest {
        val parentJob = SupervisorJob()
        val channelOperations = FakeChannelOperations(
            connectionReads = listOf(
                listOf(httpResponse(200, "ok")),
            ),
        )
        val routeService = HtxReactorElement(
            channelOperations = channelOperations,
            parentJob = parentJob,
        )
        val supervisor = NioSupervisor()
        supervisor.register(channelOperations)
        supervisor.register(routeService)
        supervisor.open()

        val element = openHtxElement(
            nioSupervisor = supervisor,
            parentJob = parentJob,
        )

        val response = element.request(parseHtxRequest("http://example.com/health"))

        assertEquals(200, response.status)
        assertEquals("ok", response.body.asString())
        assertTrue(channelOperations.recordedRequestText(0).startsWith("GET /health HTTP/1.1\r\n"))
        assertEquals(borg.trikeshed.context.ElementState.ACTIVE, routeService.state)

        element.close()
        supervisor.close()

        assertTrue(parentJob.isActive)
    }

    @Test
    fun ccekHtxElementResolvesRegisteredTlsReactorRouteService() = runTest {
        val parentJob = SupervisorJob()
        val channelOperations = FakeChannelOperations(
            connectionReads = listOf(
                listOf(
                    "server-hello".encodeToByteArray(),
                    "server-app-data".encodeToByteArray(),
                ),
            ),
        )
        val tlsBackend = FakeTlsCodecBackend()
        val routeService = HtxReactorElement(
            channelOperations = channelOperations,
            tlsBackend = tlsBackend,
            parentJob = parentJob,
        )
        val supervisor = NioSupervisor()
        supervisor.register(channelOperations)
        supervisor.register(tlsBackend)
        supervisor.register(routeService)
        supervisor.open()

        val element = openHtxElement(
            nioSupervisor = supervisor,
            parentJob = parentJob,
        )

        val response = element.request(parseHtxRequest("https://example.com/health"))

        assertEquals(200, response.status)
        assertEquals("secure", response.body.asString())

        val wire = channelOperations.recordedRequestText(0)
        assertTrue(wire.contains("client-hello"))
        assertTrue(wire.contains("enc:GET /health HTTP/1.1"))
        assertTrue(wire.contains("close-notify"))
        assertEquals(borg.trikeshed.context.ElementState.ACTIVE, routeService.state)

        element.close()
        supervisor.close()

        assertTrue(parentJob.isActive)
    }
}

private class FakeChannelOperations(
    private val connectionReads: List<List<ByteArray>>,
) : ChannelOperations {
    private var nextFd = 400
    private var nextConnection = 0
    private val readsByFd = linkedMapOf<Int, MutableList<ByteArray>>()
    private val writesByFd = linkedMapOf<Int, MutableList<ByteArray>>()
    private val connectionOrder = mutableListOf<Int>()

    override val key get() = ChannelOperations.Key

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle =
        FakeChannelHandle(this)

    override fun socket(domain: Int, type: Int, protocol: Int): Int {
        val fd = nextFd++
        val scriptedReads = connectionReads.getOrElse(nextConnection++) { emptyList() }
        readsByFd[fd] = scriptedReads.toMutableList()
        writesByFd[fd] = mutableListOf()
        connectionOrder.add(fd)
        return fd
    }

    override fun bind(fd: Int, port: Int): Int = 0

    override fun listen(fd: Int, backlog: Int): Int = 0

    override fun accept(fd: Int): Int = -1

    override fun connect(fd: Int, host: String, port: Int): Int = 0

    override fun close(fd: Int): Int = 0

    fun recordedRequestText(connectionIndex: Int): String {
        val fd = connectionOrder[connectionIndex]
        val bytes = ArrayList<Byte>()
        writesByFd[fd].orEmpty().forEach { chunk ->
            for (byte in chunk) {
                bytes.add(byte)
            }
        }
        return ByteArray(bytes.size) { bytes[it] }.decodeToString()
    }

    private fun readChunk(fd: Int): ByteArray? {
        val chunks = readsByFd[fd] ?: return null
        if (chunks.isEmpty()) {
            return null
        }
        return chunks.removeAt(0)
    }

    private fun recordWrite(fd: Int, bytes: ByteArray) {
        writesByFd.getOrPut(fd) { mutableListOf() }.add(bytes)
    }

    private class FakeChannelHandle(
        private val operations: FakeChannelOperations,
    ) : ChannelOperations.ChannelHandle {
        override val id: Int = 1

        private val pending = ArrayDeque<PendingOp>()
        private var completions: List<ChannelResult> = emptyList()

        override fun read(buffer: ByteBuffer, offset: Long): Int = -1

        override fun write(buffer: ByteBuffer, offset: Long): Int = -1

        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            pending.addLast(PendingOp(fd, buffer, userData, read = true))
            return 0
        }

        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            pending.addLast(PendingOp(fd, buffer, userData, read = false))
            return 0
        }

        override fun prepAccept(serverFd: Int, userData: Long): Int = -1

        override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1

        override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1

        override fun submit(): Int {
            val batch = mutableListOf<ChannelResult>()
            while (pending.isNotEmpty()) {
                val op = pending.removeFirst()
                if (op.read) {
                    val chunk = operations.readChunk(op.fd)
                    if (chunk == null) {
                        batch += ChannelResult(op.fd, -1, op.userData)
                    } else {
                        op.buffer.put(chunk, 0, chunk.size)
                        batch += ChannelResult(op.fd, chunk.size, op.userData)
                    }
                } else {
                    val bytes = ByteArray(op.buffer.remaining())
                    op.buffer.get(bytes)
                    operations.recordWrite(op.fd, bytes)
                    batch += ChannelResult(op.fd, bytes.size, op.userData)
                }
            }
            completions = batch
            return batch.size
        }

        override fun wait(minComplete: Int): List<ChannelResult> = completions
    }

    private data class PendingOp(
        val fd: Int,
        val buffer: ByteBuffer,
        val userData: Long,
        val read: Boolean,
    )
}

private class FakeTlsCodecBackend : TlsCodecBackend {
    private val tlsSession = TlsSession(
        protocol = TlsProtocol.TLS13,
        verified = true,
    )

    override suspend fun handshake(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult =
        TlsCodecResult(
            state.copy(
                lifecycle = TlsConnectionState.NEGOTIATING,
            ),
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.UPSTREAM_CIPHERTEXT,
                    payload = ByteSeries("client-hello"),
                ),
            ),
        )

    override suspend fun upstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult =
        TlsCodecResult(
            state.copy(
                lifecycle = TlsConnectionState.OPEN,
                session = tlsSession,
                pendingUpstreamPlaintext = payload.clone(),
                pendingUpstreamCiphertext = ByteSeries("enc:${payload.asString()}"),
            ),
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.UPSTREAM_CIPHERTEXT,
                    payload = ByteSeries("enc:${payload.asString()}"),
                    session = tlsSession,
                ),
            ),
        )

    override suspend fun downstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult =
        if (state.session == null) {
            TlsCodecResult(
                state.copy(
                    lifecycle = TlsConnectionState.OPEN,
                    session = tlsSession,
                    pendingDownstreamCiphertext = payload.clone(),
                ),
                tlsFrames(
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.HANDSHAKE,
                        session = tlsSession,
                    ),
                ),
            )
        } else {
            val plaintext = ByteSeries(httpResponse(200, "secure"))
            TlsCodecResult(
                state.copy(
                    lifecycle = TlsConnectionState.OPEN,
                    session = tlsSession,
                    pendingDownstreamCiphertext = payload.clone(),
                    pendingDownstreamPlaintext = plaintext.clone(),
                ),
                tlsFrames(
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.DOWNSTREAM_PLAINTEXT,
                        payload = plaintext,
                        session = tlsSession,
                    ),
                ),
            )
        }

    override suspend fun close(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult =
        TlsCodecResult(
            state.copy(
                lifecycle = TlsConnectionState.CLOSED,
                session = state.session ?: tlsSession,
            ),
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.CLOSE_NOTIFY,
                    payload = ByteSeries("close-notify"),
                    session = state.session ?: tlsSession,
                ),
            ),
        )
}

private fun httpResponse(
    status: Int,
    body: String,
): ByteArray =
    "HTTP/1.1 $status OK\r\nContent-Length: ${body.encodeToByteArray().size}\r\nConnection: close\r\n\r\n$body"
        .encodeToByteArray()
