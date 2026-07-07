package borg.trikeshed.htx

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toArray
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

object HtxClientKey : AsyncContextKey<HtxClientReactorElement>()

data class HtxClientOptions(
    val channelParallelism: Int = 4,
    val blobChunkBytes: Long = 4L * 1024L * 1024L,
    val maxRedirects: Int = 8,
) {
    init {
        require(channelParallelism > 0) { "HTX client channelParallelism must be positive." }
        require(blobChunkBytes > 0) { "HTX client blobChunkBytes must be positive." }
        require(maxRedirects >= 0) { "HTX client maxRedirects cannot be negative." }
    }
}

enum class HtxClientStage {
    WORKER_OPENED,
    WORKER_CLOSED,
    TRANSFER_OPENED,
    REQUEST_DISPATCHED,
    REDIRECTED,
    RESPONSE_RECEIVED,
    TRANSFER_CLOSED,
    TRANSFER_FAILED,
}

data class HtxClientFrame(
    val clientOrdinal: Long,
    val transferOrdinal: Long? = null,
    val channelOrdinal: Int? = null,
    val stage: HtxClientStage,
    val range: HtxRange? = null,
    val request: HtxRequest? = null,
    val response: HtxResponse? = null,
    val failure: String? = null,
) : FanoutEvent {
    override val eventType: Int = 0x48545843
}

interface HtxClientFrameSubscriber {
    suspend fun onHtxClientFrame(frame: HtxClientFrame)
}

data class HtxBlobChannel(
    val ordinal: Int,
    val range: HtxRange,
)

data class HtxTransferResult(
    val transferOrdinal: Long,
    val channelOrdinal: Int?,
    val range: HtxRange?,
    val request: HtxRequest,
    val response: HtxResponse,
)

open class HtxClientReactorElement(
    private val routeService: HtxRouteService,
    private val options: HtxClientOptions = HtxClientOptions(),
    private val workerContext: CoroutineContext = Dispatchers.Default,
    parentJob: Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
    override val fanoutSubscribers: List<AsyncContextElement> = emptyList(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = HtxClientKey

    private val ordinalMutex = Mutex()
    private val fanoutMutex = Mutex()
    private var nextClientOrdinal = 1L
    private var nextTransferOrdinal = 1L

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
        }
    }

    suspend fun request(
        url: String,
        range: HtxRange? = null,
        method: HtxMethod = HtxMethod.GET,
        headers: HtxHeaders = emptyHtxHeaders(),
    ): HtxResponse =
        request(
            parseHtxRequest(
                url = url,
                range = range,
                method = method,
            ).copy(headers = headers),
        )

    suspend fun request(request: HtxRequest): HtxResponse {
        ensureActive()
        val clientOrdinal = nextClientOrdinal()
        val transfer = transferElement(
            clientOrdinal = clientOrdinal,
            channelOrdinal = null,
            request = request,
        )
        return withContext(workerContext) {
            transfer.execute().response
        }
    }

    fun blob(
        url: String,
        contentLength: Long,
        headers: HtxHeaders = emptyHtxHeaders(),
    ): Flow<HtxTransferResult> = channelFlow {
        ensureActive()
        require(contentLength >= 0) { "HTX blob contentLength cannot be negative." }

        val clientOrdinal = nextClientOrdinal()
        val plan = planBlobChannels(contentLength, options.blobChunkBytes)
        if (plan.isEmpty()) {
            return@channelFlow
        }

        val work = Channel<HtxTransferElement>(Channel.BUFFERED)
        val workerCount = minOf(options.channelParallelism, plan.size)

        repeat(workerCount) { workerOrdinal ->
            val worker = HtxChannelWorkerElement(
                clientOrdinal = clientOrdinal,
                workerOrdinal = workerOrdinal,
                parentJob = supervisor,
                frameSink = ::channelize,
            )
            launch(workerContext) {
                worker.run(work) { result ->
                    send(result)
                }
            }
        }

        plan.forEach { channel ->
            val request = parseHtxRequest(
                url = url,
                range = channel.range,
            ).copy(headers = headers)
            work.send(
                transferElement(
                    clientOrdinal = clientOrdinal,
                    channelOrdinal = channel.ordinal,
                    request = request,
                ),
            )
        }
        work.close()
    }

    suspend fun blobBytes(
        url: String,
        contentLength: Long,
        headers: HtxHeaders = emptyHtxHeaders(),
    ): ByteSeries {
        require(contentLength <= Int.MAX_VALUE) {
            "HTX blobBytes is only for bounded in-memory blobs; stream larger blobs with blob()."
        }
        val chunks = blob(url, contentLength, headers)
            .toList()
            .sortedBy { it.channelOrdinal ?: 0 }
        val totalBytes = chunks.sumOf { it.response.body.toArray().size }
        val joined = ByteArray(totalBytes)
        var offset = 0
        chunks.forEach { chunk ->
            val bytes = chunk.response.body.toArray()
            bytes.copyInto(joined, offset)
            offset += bytes.size
        }
        return ByteSeries(joined)
    }

    override suspend fun close() {
        if (ownedSupervisor != null && ownedSupervisor.state.isLessThan(ElementState.CLOSED)) {
            ownedSupervisor.close()
        }
        super.close()
    }

    internal suspend fun exchangeFollowingRedirects(
        clientOrdinal: Long,
        transferOrdinal: Long,
        channelOrdinal: Int?,
        request: HtxRequest,
        redirectDepth: Int = 0,
    ): HtxResponse {
        channelize(
            HtxClientFrame(
                clientOrdinal = clientOrdinal,
                transferOrdinal = transferOrdinal,
                channelOrdinal = channelOrdinal,
                stage = HtxClientStage.REQUEST_DISPATCHED,
                range = request.range,
                request = request,
            ),
        )

        val result = routeService.exchange(
            HtxExchangeState(
                exchangeOrdinal = transferOrdinal,
                lifecycle = HtxExchangeLifecycle.REQUESTED,
                request = request,
            ),
            request,
        )

        val response = result.state.response ?: error(
            result.state.failure ?: "HTX client route service did not produce a response.",
        )

        val location = response.headers.headerValue("Location")
        if (response.status in 300..399 && location != null && redirectDepth < options.maxRedirects) {
            val nextUrl = resolveRedirectUrl(request, location)
            val redirected = parseHtxRequest(
                url = nextUrl,
                range = request.range,
                method = request.method,
                body = request.body,
            ).copy(headers = request.headers)

            channelize(
                HtxClientFrame(
                    clientOrdinal = clientOrdinal,
                    transferOrdinal = transferOrdinal,
                    channelOrdinal = channelOrdinal,
                    stage = HtxClientStage.REDIRECTED,
                    range = request.range,
                    request = redirected,
                    response = response,
                ),
            )

            return exchangeFollowingRedirects(
                clientOrdinal = clientOrdinal,
                transferOrdinal = transferOrdinal,
                channelOrdinal = channelOrdinal,
                request = redirected,
                redirectDepth = redirectDepth + 1,
            )
        }

        channelize(
            HtxClientFrame(
                clientOrdinal = clientOrdinal,
                transferOrdinal = transferOrdinal,
                channelOrdinal = channelOrdinal,
                stage = HtxClientStage.RESPONSE_RECEIVED,
                range = request.range,
                request = request,
                response = response,
            ),
        )

        return response
    }

    internal suspend fun channelize(frame: HtxClientFrame) {
        fanoutMutex.withLock {
            fanoutSubscribers
                .filterIsInstance<HtxClientFrameSubscriber>()
                .forEach { it.onHtxClientFrame(frame) }

            fanoutSubscribers
                .filterIsInstance<FanoutEventSubscriber>()
                .forEach { it.onFanoutEvent(frame) }
        }
    }

    private suspend fun transferElement(
        clientOrdinal: Long,
        channelOrdinal: Int?,
        request: HtxRequest,
    ): HtxTransferElement =
        HtxTransferElement(
            client = this,
            clientOrdinal = clientOrdinal,
            transferOrdinal = nextTransferOrdinal(),
            channelOrdinal = channelOrdinal,
            request = request,
            parentJob = supervisor,
        )

    private suspend fun nextClientOrdinal(): Long =
        ordinalMutex.withLock { nextClientOrdinal++ }

    private suspend fun nextTransferOrdinal(): Long =
        ordinalMutex.withLock { nextTransferOrdinal++ }

    private fun ensureActive() {
        check(state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.DRAINING)) {
            "HTX client reactor must be open before dispatch."
        }
    }
}

object HtxTransferKey : AsyncContextKey<HtxTransferElement>()

class HtxTransferElement(
    private val client: HtxClientReactorElement,
    private val clientOrdinal: Long,
    private val transferOrdinal: Long,
    private val channelOrdinal: Int?,
    private val request: HtxRequest,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = HtxTransferKey

    suspend fun execute(): HtxTransferResult {
        if (state == ElementState.CREATED) {
            open()
        }
        state = ElementState.ACTIVE
        client.channelize(
            HtxClientFrame(
                clientOrdinal = clientOrdinal,
                transferOrdinal = transferOrdinal,
                channelOrdinal = channelOrdinal,
                stage = HtxClientStage.TRANSFER_OPENED,
                range = request.range,
                request = request,
            ),
        )

        return try {
            val response = client.exchangeFollowingRedirects(
                clientOrdinal = clientOrdinal,
                transferOrdinal = transferOrdinal,
                channelOrdinal = channelOrdinal,
                request = request,
            )
            HtxTransferResult(
                transferOrdinal = transferOrdinal,
                channelOrdinal = channelOrdinal,
                range = request.range,
                request = request,
                response = response,
            )
        } catch (t: Throwable) {
            client.channelize(
                HtxClientFrame(
                    clientOrdinal = clientOrdinal,
                    transferOrdinal = transferOrdinal,
                    channelOrdinal = channelOrdinal,
                    stage = HtxClientStage.TRANSFER_FAILED,
                    range = request.range,
                    request = request,
                    failure = t.message ?: "HTX transfer failed.",
                ),
            )
            throw t
        } finally {
            client.channelize(
                HtxClientFrame(
                    clientOrdinal = clientOrdinal,
                    transferOrdinal = transferOrdinal,
                    channelOrdinal = channelOrdinal,
                    stage = HtxClientStage.TRANSFER_CLOSED,
                    range = request.range,
                    request = request,
                ),
            )
            close()
        }
    }
}

object HtxChannelWorkerKey : AsyncContextKey<HtxChannelWorkerElement>()

class HtxChannelWorkerElement(
    private val clientOrdinal: Long,
    private val workerOrdinal: Int,
    parentJob: Job? = null,
    private val frameSink: suspend (HtxClientFrame) -> Unit,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = HtxChannelWorkerKey

    suspend fun run(
        transfers: Channel<HtxTransferElement>,
        resultSink: suspend (HtxTransferResult) -> Unit,
    ) {
        if (state == ElementState.CREATED) {
            open()
        }
        state = ElementState.ACTIVE
        frameSink(
            HtxClientFrame(
                clientOrdinal = clientOrdinal,
                channelOrdinal = workerOrdinal,
                stage = HtxClientStage.WORKER_OPENED,
            ),
        )

        try {
            for (transfer in transfers) {
                resultSink(transfer.execute())
            }
        } finally {
            frameSink(
                HtxClientFrame(
                    clientOrdinal = clientOrdinal,
                    channelOrdinal = workerOrdinal,
                    stage = HtxClientStage.WORKER_CLOSED,
                ),
            )
            close()
        }
    }
}

suspend fun openHtxClientReactorElement(
    routeService: HtxRouteService? = null,
    nioSupervisor: NioSupervisor? = null,
    options: HtxClientOptions = HtxClientOptions(),
    parentJob: Job? = null,
    subscribers: List<AsyncContextElement> = emptyList(),
): HtxClientReactorElement {
    val contextRouteService = currentCoroutineContext()[HtxRouteService.Key]
    val contextSupervisor = currentCoroutineContext()[NioSupervisor.Key]
    val activeSupervisor = nioSupervisor ?: contextSupervisor ?: NioSupervisor()
    val ownsSupervisor = nioSupervisor == null && contextSupervisor == null

    if (activeSupervisor.state == ElementState.CREATED) {
        activeSupervisor.open()
    }

    val resolvedService = routeService
        ?: contextRouteService
        ?: activeSupervisor.service<HtxRouteService>()
        ?: error("HtxClientReactorElement requires HtxRouteService in NioSupervisor.")

    return HtxClientReactorElement(
        routeService = resolvedService,
        options = options,
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
        fanoutSubscribers = subscribers,
    ).also { it.open() }
}

private fun planBlobChannels(
    contentLength: Long,
    chunkBytes: Long,
): List<HtxBlobChannel> {
    if (contentLength == 0L) {
        return emptyList()
    }
    val channels = mutableListOf<HtxBlobChannel>()
    var offset = 0L
    var ordinal = 0
    while (offset < contentLength) {
        val end = minOf(contentLength - 1, offset + chunkBytes - 1)
        channels += HtxBlobChannel(
            ordinal = ordinal++,
            range = HtxRange(offset, end),
        )
        offset = end + 1
    }
    return channels
}

private fun resolveRedirectUrl(
    request: HtxRequest,
    location: String,
): String {
    val target = request.target
    val trimmed = location.trim()
    if ("://" in trimmed) {
        return trimmed
    }

    val scheme = when (target.transportProtocol) {
        HtxTransportProtocol.HTTP -> "http"
        HtxTransportProtocol.HTTPS -> "https"
    }
    val authority = if (target.port == target.transportProtocol.defaultPort()) {
        target.host
    } else {
        "${target.host}:${target.port}"
    }

    return when {
        trimmed.startsWith("/") -> "$scheme://$authority$trimmed"
        trimmed.startsWith("?") -> "$scheme://$authority${target.requestPath.substringBefore("?")}$trimmed"
        else -> {
            val baseDir = target.requestPath.substringBeforeLast("/", "")
            val prefix = if (baseDir.isEmpty()) "/" else "$baseDir/"
            "$scheme://$authority$prefix$trimmed"
        }
    }
}

private fun HtxTransportProtocol.defaultPort(): Int =
    when (this) {
        HtxTransportProtocol.HTTP -> 80
        HtxTransportProtocol.HTTPS -> 443
    }
