package borg.trikeshed.htx

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HtxClientReactorElementTest {
    @Test
    fun ccekOpenHtxClientReactorElementConstructsActiveElementAndEmitsFrames() = runTest {
        val frameSubscriber = RecordingHtxClientFrameSubscriber()
        val fanoutSubscriber = RecordingHtxClientFanoutSubscriber()
        val element = openHtxClientReactorElement(
            routeService = RecordingClientRouteService(),
            subscribers = listOf(frameSubscriber, fanoutSubscriber),
        )

        val response = element.request("http://example.com/health")

        assertEquals(ElementState.ACTIVE, element.state)
        assertEquals(200, response.status)
        assertEquals("ok", response.body.asString())
        assertEquals(
            listOf(
                HtxClientStage.TRANSFER_OPENED,
                HtxClientStage.REQUEST_DISPATCHED,
                HtxClientStage.RESPONSE_RECEIVED,
                HtxClientStage.TRANSFER_CLOSED,
            ),
            frameSubscriber.frames.map { it.stage },
        )
        assertEquals(frameSubscriber.frames.size, fanoutSubscriber.events.size)
        assertTrue(fanoutSubscriber.events.all { it is HtxClientFrame })

        element.close()
    }

    @Test
    fun ccekOpenHtxClientReactorElementResolvesRouteServiceFromSupervisor() = runTest {
        val supervisor = NioSupervisor()
        val routeService = RecordingClientRouteService()
        supervisor.register(routeService)
        supervisor.open()

        val element = openHtxClientReactorElement(nioSupervisor = supervisor)
        val response = element.request(parseHtxRequest("http://example.com/supervisor"))

        assertEquals(ElementState.ACTIVE, element.state)
        assertEquals(200, response.status)
        assertEquals("supervisor", response.body.asString())

        element.close()
        supervisor.close()
    }

    @Test
    fun ccekClientRequestFollowsRedirectsAndEmitsRedirectFrame() = runTest {
        val routeService = RecordingClientRouteService()
        val subscriber = RecordingHtxClientFrameSubscriber()
        val element = openHtxClientReactorElement(
            routeService = routeService,
            subscribers = listOf(subscriber),
        )

        val response = element.request("http://example.com/redirect")

        assertEquals(200, response.status)
        assertEquals("redirected", response.body.asString())
        assertEquals(listOf("/redirect", "/target"), routeService.requestPaths())
        assertTrue(subscriber.frames.any { it.stage == HtxClientStage.REDIRECTED })
        assertEquals(
            "http://example.com/target",
            subscriber.frames.first { it.stage == HtxClientStage.REDIRECTED }.request?.let { redirectedUrl(it) },
        )

        element.close()
    }

    @Test
    fun ccekBlobStreamingUsesWorkerPoolAndBlobBytesReassemblesInOrder() = runTest {
        val routeService = RecordingClientRouteService()
        val subscriber = RecordingHtxClientFrameSubscriber()
        val element = openHtxClientReactorElement(
            routeService = routeService,
            options = HtxClientOptions(channelParallelism = 3, blobChunkBytes = 4),
            subscribers = listOf(subscriber),
        )

        val streamed = element.blob("http://example.com/blob", contentLength = 10)
            .toList()
            .sortedBy { it.channelOrdinal }
        val blobBytes = element.blobBytes("http://example.com/blob", contentLength = 10)

        assertEquals(listOf(0, 1, 2), streamed.map { it.channelOrdinal })
        assertEquals(
            listOf(
                HtxRange(0, 3),
                HtxRange(4, 7),
                HtxRange(8, 9),
            ),
            streamed.map { it.range },
        )
        assertContentEquals("abcdefghij".encodeToByteArray(), blobBytes.toArray())
        assertTrue(routeService.maxConcurrentRequests >= 2)

        val workerEvents = subscriber.frames.filter { it.stage == HtxClientStage.WORKER_OPENED }
        assertEquals(6, workerEvents.size)
        assertNotNull(subscriber.frames.firstOrNull { it.stage == HtxClientStage.RESPONSE_RECEIVED && it.range == HtxRange(0, 3) })
        assertNotNull(subscriber.frames.firstOrNull { it.stage == HtxClientStage.RESPONSE_RECEIVED && it.range == HtxRange(4, 7) })
        assertNotNull(subscriber.frames.firstOrNull { it.stage == HtxClientStage.RESPONSE_RECEIVED && it.range == HtxRange(8, 9) })

        element.close()
    }
}

private class RecordingHtxClientFrameSubscriber : AsyncContextElement(), HtxClientFrameSubscriber {
    companion object Key : CoroutineContext.Key<RecordingHtxClientFrameSubscriber>
    override val key: CoroutineContext.Key<*> get() = Key

    val frames = mutableListOf<HtxClientFrame>()

    override suspend fun onHtxClientFrame(frame: HtxClientFrame) {
        frames += frame
    }
}

private class RecordingHtxClientFanoutSubscriber : AsyncContextElement(), FanoutEventSubscriber {
    companion object Key : CoroutineContext.Key<RecordingHtxClientFanoutSubscriber>
    override val key: CoroutineContext.Key<*> get() = Key

    val events = mutableListOf<FanoutEvent>()

    override suspend fun onFanoutEvent(event: FanoutEvent) {
        events += event
    }
}

private class RecordingClientRouteService : AsyncContextElement(), HtxRouteService {
    override val key: CoroutineContext.Key<*> get() = HtxRouteService

    private val requests = mutableListOf<HtxRequest>()
    private val requestsMutex = Mutex()
    private val concurrencyMutex = Mutex()
    private var activeRequests = 0
    var maxConcurrentRequests: Int = 0
        private set

    suspend fun requestPaths(): List<String> = requestsMutex.withLock {
        requests.map { it.target.requestPath }
    }

    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult {
        requestsMutex.withLock {
            requests += request
        }
        concurrencyMutex.withLock {
            activeRequests += 1
            if (activeRequests > maxConcurrentRequests) {
                maxConcurrentRequests = activeRequests
            }
        }

        try {
            request.range?.let { delay(25) }
            val response = responseFor(request)
            return HtxExchangeResult(
                state.copy(
                    lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request,
                    response = response,
                ),
            )
        } finally {
            concurrencyMutex.withLock {
                activeRequests -= 1
            }
        }
    }

    private fun responseFor(request: HtxRequest): HtxResponse =
        when (request.target.requestPath) {
            "/health" -> HtxResponse(200, ByteSeries("ok"))
            "/supervisor" -> HtxResponse(200, ByteSeries("supervisor"))
            "/redirect" -> HtxResponse(302, headers = htxHeaders("Location" j "/target"))
            "/target" -> HtxResponse(200, ByteSeries("redirected"))
            "/blob" -> HtxResponse(
                206,
                body = ByteSeries(blobChunkFor(request.range)),
                headers = request.range?.let { htxHeaders("Content-Range" j "bytes ${it.startInclusive}-${it.endInclusive}/10") }
                    ?: emptyHtxHeaders(),
            )
            else -> HtxResponse(404, ByteSeries("not found"))
        }
}

private fun redirectedUrl(request: HtxRequest): String {
    val scheme = when (request.target.transportProtocol) {
        HtxTransportProtocol.HTTP -> "http"
        HtxTransportProtocol.HTTPS -> "https"
    }
    val authority = if (
        (request.target.transportProtocol == HtxTransportProtocol.HTTP && request.target.port == 80) ||
        (request.target.transportProtocol == HtxTransportProtocol.HTTPS && request.target.port == 443)
    ) {
        request.target.host
    } else {
        "${request.target.host}:${request.target.port}"
    }
    return "$scheme://$authority${request.target.requestPath}"
}

private fun blobChunkFor(range: HtxRange?): ByteArray {
    val source = "abcdefghij".encodeToByteArray()
    requireNotNull(range) { "blob requests must include a range" }
    return source.copyOfRange(range.startInclusive.toInt(), range.endInclusive.toInt() + 1)
}
