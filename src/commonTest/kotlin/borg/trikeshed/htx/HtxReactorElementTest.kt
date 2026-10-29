package borg.trikeshed.htx

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.modelmux.BrainNoRoute
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HtxReactorElementTest {
    @Test
    fun responseByteLimitRejectsHeadersAndBodiesBeforeFurtherReadsAndClosesTheConnection() = runTest {
        val operations = FakeChannelOperations(listOf(
            listOf("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".encodeToByteArray()),
            listOf("HTTP/1.1 200 OK\r\n\r\n".encodeToByteArray(), ByteArray(96)),
        ))
        val reactor = openHtxReactorElement(channelOperations = operations)
        try {
            repeat(2) {
                val result = reactor.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/").copy(maxResponseBytes = 64))
                assertEquals(HtxExchangeLifecycle.FAILED, result.state.lifecycle)
                assertTrue(result.state.failure.orEmpty().contains("response byte limit"))
            }
            assertEquals(2, operations.closedHandles)
            assertEquals(2, operations.closedDescriptors.size)
            assertFailsWith<IllegalArgumentException> { parseHtxRequest("http://example.com/").copy(maxResponseBytes = 0) }
        } finally { reactor.drain() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ownerCancellationSettlesAnExchangeAdmittedBeforeTheWorkerStarts() = runTest {
        val owner = SupervisorJob()
        val operations = FakeChannelOperations(emptyList())
        val reactor = openHtxReactorElement(channelOperations = operations, parentJob = owner)
        val exchange = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { reactor.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/")) }
        }
        assertTrue(!exchange.isCompleted)
        owner.cancel()
        runCurrent()
        assertTrue(exchange.isCompleted, "An admitted request must receive the worker's terminal failure")
        assertTrue(exchange.await().exceptionOrNull() is CancellationException)
        reactor.drain()
        owner.join()
        assertEquals(0, operations.closedHandles)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun seatTimeoutReachesReadAndWriteCompletionsWithoutChangingTheDefault() = runTest {
        val request = parseHtxRequest("http://example.com/v1/chat/completions")
        for (invalid in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { request.copy(timeoutMs = invalid) }
        }
        val body = """{"choices":[{"message":{"content":"draft ready"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}"""
        val operations = FakeChannelOperations(
            connectionReads = listOf(listOf(httpResponse(200, body)), listOf(httpResponse(200, body))),
            completionDelayMs = 40_000L,
            nowMs = { testScheduler.currentTime },
        )
        val reactor = openHtxReactorElement(channelOperations = operations)
        val htx = openHtxElement(routeService = reactor)
        val brain = BrainClient(apiKey = "timeout-fixture", base = "http://example.com/v1", model = "timeout-fixture")
        try {
            withContext(htx) {
                val start = testScheduler.currentTime
                val answer = brain.chatSeat(listOf("user" to "prepare"), timeoutMs = 90_000L)
                assertEquals("draft ready", answer.first)
                // Both the first WRITE and the READ wait beyond the former 30-second limit.
                assertEquals(80_000L, testScheduler.currentTime - start)

                val defaultStart = testScheduler.currentTime
                assertFailsWith<BrainNoRoute> { brain.chatSeat(listOf("user" to "prepare again")) }
                assertEquals(30_000L, testScheduler.currentTime - defaultStart)
            }
            assertEquals(2, operations.closedHandles)
            assertEquals(2, operations.closedDescriptors.size)
        } finally {
            htx.close()
            reactor.drain()
        }
    }

    @Test
    fun provider_failure_still_closes_every_resource_and_all_drain_callers_wait() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = mutableListOf<Int>()
        val failure = IllegalStateException("provider cleanup failed")
        fun provider(id: Int) = object : kotlin.coroutines.CoroutineContext.Element, borg.trikeshed.context.Drain {
            override val key = object : kotlin.coroutines.CoroutineContext.Key<kotlin.coroutines.CoroutineContext.Element> {}
            override suspend fun drain() {
                try {
                    if (id == 2) throw failure
                    entered.complete(Unit)
                    release.await()
                } finally { closed.add(id) }
            }
        }
        val nio = NioSupervisor()
        nio.register(provider(1))
        nio.register(provider(2))
        nio.open()
        try {
            val first = async { runCatching { nio.drain() } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { nio.drain() } }
            assertTrue(!first.isCompleted && !second.isCompleted)
            release.complete(Unit)
            for (outcome in listOf(first.await(), second.await())) {
                assertEquals(failure.message, outcome.exceptionOrNull()?.message)
            }
            assertEquals(listOf(1, 2), closed.sorted())
        } finally {
            release.complete(Unit)
            runCatching { nio.drain() }
        }
    }

    @Test
    fun fragmentedHeaderCompletesBeforeThePeerCloses() = runTest {
        val response = httpResponse(200, "x".repeat(2048))
        val operations = FakeChannelOperations(
            listOf(listOf(response.copyOfRange(0, 7), response.copyOfRange(7, response.size))),
            endOfStreamResult = -11,
        )
        val element = openHtxReactorElement(channelOperations = operations)
        try {
            val result = element.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/"))
            assertEquals(HtxExchangeLifecycle.RESPONDED, result.state.lifecycle)
            assertEquals("x".repeat(2048), result.state.response?.body?.asString())
        } finally { element.drain() }
    }

    @Test
    fun nonzeroChunkSizeEndingInZeroDoesNotCompleteTheResponse() = runTest {
        val body = "{\"ok\":true}     "
        val operations = FakeChannelOperations(
            listOf(listOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n".encodeToByteArray(),
                "$body\r\n0\r\n".encodeToByteArray(),
                "\r\n".encodeToByteArray(),
            )),
            endOfStreamResult = -11,
        )
        val element = openHtxReactorElement(channelOperations = operations)
        try {
            val result = element.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/"))
            assertEquals(HtxExchangeLifecycle.RESPONDED, result.state.lifecycle)
            assertEquals(body, result.state.response?.body?.asString())
            assertEquals(0, operations.remainingReads(0))
        } finally { element.drain() }
    }

    @Test
    fun terminalMarkerInsideChunkDataDoesNotCompleteTheResponse() = runTest {
        val operations = FakeChannelOperations(
            listOf(listOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7\r\na0\r\n\r\n".encodeToByteArray(),
                "b\r\n0\r\n\r\n".encodeToByteArray(),
            )),
            endOfStreamResult = -11,
        )
        val element = openHtxReactorElement(channelOperations = operations)
        try {
            val result = element.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/"))
            assertEquals(HtxExchangeLifecycle.RESPONDED, result.state.lifecycle)
            assertEquals("a0\r\n\r\nb", result.state.response?.body?.asString())
            assertEquals(0, operations.remainingReads(0))
        } finally { element.drain() }
    }

    @Test
    fun chunkTrailersRequireTheirFinalEmptyLineButNotConnectionClosure() = runTest {
        val operations = FakeChannelOperations(
            listOf(listOf(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;name=value\r\nhello\r\n0\r\n".encodeToByteArray(),
                "Checksum: ok\r\n".encodeToByteArray(),
                "\r\n".encodeToByteArray(),
            )),
            endOfStreamResult = -11,
        )
        val element = openHtxReactorElement(channelOperations = operations)
        try {
            val result = element.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/"))
            assertEquals(HtxExchangeLifecycle.RESPONDED, result.state.lifecycle)
            assertEquals("hello", result.state.response?.body?.asString())
            assertEquals(0, operations.remainingReads(0))
        } finally { element.drain() }
    }

    @Test
    fun connectFailureClosesSocketAndRingBeforeReturningFailure() = runTest {
        val operations = FakeChannelOperations(emptyList(), connectResult = -111)
        val element = openHtxReactorElement(channelOperations = operations)
        val response = element.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/"))

        assertEquals(HtxExchangeLifecycle.FAILED, response.state.lifecycle)
        assertEquals(listOf(400), operations.closedDescriptors)
        assertEquals(1, operations.closedHandles)
        element.drain()
        assertTrue(element.supervisor.isCompleted)
    }

    @Test
    fun resolutionFailureDoesNotOpenSocketOrRing() = runTest {
        val operations = FakeChannelOperations(emptyList(), address = null)
        val element = openHtxReactorElement(channelOperations = operations)
        val response = element.exchange(HtxExchangeState(), parseHtxRequest("http://example.com/"))

        assertEquals(HtxExchangeLifecycle.FAILED, response.state.lifecycle)
        assertEquals(0, operations.openedHandles)
        assertTrue(operations.closedDescriptors.isEmpty())
        element.drain()
    }

    @Test
    fun ccekOpenHtxReactorElementWithChannelOperationsConstructsActiveElement() = runTest {
        val channelOperations = FakeChannelOperations(
            connectionReads = listOf(
                listOf(httpResponse(200, "ok")),
            ),
        )

        val routeService = openHtxReactorElement(channelOperations = channelOperations)
        val response = routeService.exchange(
            HtxExchangeState(exchangeOrdinal = 1),
            parseHtxRequest("http://example.com/health"),
        )

        assertTrue(routeService.supervisor.isActive)
        assertEquals(HtxExchangeLifecycle.RESPONDED, response.state.lifecycle)
        assertEquals(200, response.state.response?.status)
        assertTrue(channelOperations.recordedRequestText(0).startsWith("GET /health HTTP/1.1\r\n"))

        routeService.close()
    }

    @Test
    fun ccekOpenHtxReactorElementFromSupervisorConstructsActiveTlsElement() = runTest {
        val channelOperations = FakeChannelOperations(
            connectionReads = listOf(
                listOf(
                    "server-hello".encodeToByteArray(),
                    "server-app-data".encodeToByteArray(),
                ),
            ),
        )
        val tlsBackend = FakeTlsCodecBackend()
        val supervisor = NioSupervisor()
        supervisor.register(channelOperations)
        supervisor.register(tlsBackend)
        supervisor.open()

        val routeService = openHtxReactorElement(nioSupervisor = supervisor)
        val response = routeService.exchange(
            HtxExchangeState(exchangeOrdinal = 1),
            parseHtxRequest("https://example.com/health"),
        )

        assertTrue(routeService.supervisor.isActive)
        assertEquals(HtxExchangeLifecycle.RESPONDED, response.state.lifecycle)
        assertEquals(200, response.state.response?.status)

        val wire = channelOperations.recordedRequestText(0)
        assertTrue(wire.contains("client-hello"))
        assertTrue(wire.contains("enc:GET /health HTTP/1.1"))

        routeService.close()
        supervisor.close()
    }

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
        assertTrue(routeService.supervisor.isActive)

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
        assertTrue(routeService.supervisor.isActive)

        element.close()
        supervisor.close()

        assertTrue(parentJob.isActive)
    }
}

private class FakeChannelOperations(
    private val connectionReads: List<List<ByteArray>>,
    val connectResult: Int = 0,
    private val address: ByteArray? = byteArrayOf(127, 0, 0, 1),
    val endOfStreamResult: Int = 0,
    val completionDelayMs: Long = 0L,
    val nowMs: () -> Long = { 0L },
) : ChannelOperations {
    val closedDescriptors = mutableListOf<Int>()
    var openedHandles = 0
    var closedHandles = 0
    private var nextFd = 400
    private var nextConnection = 0
    private val readsByFd = linkedMapOf<Int, MutableList<ByteArray>>()
    private val writesByFd = linkedMapOf<Int, MutableList<ByteArray>>()
    private val connectionOrder = mutableListOf<Int>()

    override val key get() = ChannelOperations.Key

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle {
        openedHandles++
        return FakeChannelHandle(this)
    }

    override fun resolve(host: String): ByteArray? = address

    fun socket(): Int {
        val fd = nextFd++
        val scriptedReads = connectionReads.getOrElse(nextConnection++) { emptyList() }
        readsByFd[fd] = scriptedReads.toMutableList()
        writesByFd[fd] = mutableListOf()
        connectionOrder.add(fd)
        return fd
    }

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

    fun remainingReads(connectionIndex: Int): Int = readsByFd[connectionOrder[connectionIndex]].orEmpty().size

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

        private val pending = ArrayDeque<() -> ChannelResult>()
        private var completions: List<ChannelResult> = emptyList()
        private var pendingDelayMs = 0L
        private var availableAtMs = 0L
        private var hasWritten = false

        override fun read(buffer: ByteBuffer, offset: Long): Int = -1

        override fun write(buffer: ByteBuffer, offset: Long): Int = -1

        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            pendingDelayMs = operations.completionDelayMs
            pending.addLast {
                val chunk = operations.readChunk(fd)
                if (chunk == null) ChannelResult(fd, operations.endOfStreamResult, userData)
                else {
                    buffer.put(chunk, 0, chunk.size)
                    ChannelResult(fd, chunk.size, userData)
                }
            }
            return 0
        }

        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            pendingDelayMs = if (hasWritten) 0L else operations.completionDelayMs
            hasWritten = true
            pending.addLast {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                operations.recordWrite(fd, bytes)
                ChannelResult(fd, bytes.size, userData)
            }
            return 0
        }

        override fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long): Int {
            pending.addLast { ChannelResult(domain, operations.socket(), userData) }
            return 0
        }

        override fun prepConnect(fd: Int, address: ByteBuffer, userData: Long): Int {
            pending.addLast { ChannelResult(fd, operations.connectResult, userData) }
            return 0
        }

        override fun prepClose(fd: Int, userData: Long): Int {
            pending.addLast {
                operations.closedDescriptors.add(fd)
                ChannelResult(fd, 0, userData)
            }
            return 0
        }

        override fun close() { operations.closedHandles++ }

        override fun prepAccept(serverFd: Int, userData: Long): Int = -1

        override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1

        override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1

        override fun submit(): Int {
            val batch = mutableListOf<ChannelResult>()
            while (pending.isNotEmpty()) {
                batch += pending.removeFirst().invoke()
            }
            completions = batch
            availableAtMs = operations.nowMs() + pendingDelayMs
            pendingDelayMs = 0L
            return batch.size
        }

        override fun wait(minComplete: Int): List<ChannelResult> =
            if (operations.nowMs() < availableAtMs) emptyList()
            else completions.also { completions = emptyList() }
    }
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
