package borg.trikeshed.htx

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

typealias HtxFrames = Series<HtxFrame>
typealias HtxExchangeResult = Join<HtxExchangeState, HtxFrames>

fun emptyHtxFrames(): HtxFrames = emptySeriesOf()

fun htxFrames(vararg frames: HtxFrame): HtxFrames = frames.asList().toSeries()

fun HtxExchangeResult(
    state: HtxExchangeState,
    frames: HtxFrames = emptyHtxFrames(),
): HtxExchangeResult = state j frames

val HtxExchangeResult.state: HtxExchangeState get() = a
val HtxExchangeResult.frames: HtxFrames get() = b

enum class HtxFlowStage {
    REQUEST,
    RESPONSE,
    FAILURE,
}

enum class HtxExchangeLifecycle {
    CREATED,
    REQUESTED,
    RESPONDED,
    FAILED,
    CLOSED,
}

data class HtxExchangeState(
    val exchangeOrdinal: Long = 0,
    val baseUrl: String = "",
    val lifecycle: HtxExchangeLifecycle = HtxExchangeLifecycle.CREATED,
    val request: HtxRequest? = null,
    val response: HtxResponse? = null,
    val failure: String? = null,
)

data class HtxFrame(
    val exchangeOrdinal: Long,
    val stage: HtxFlowStage,
    val request: HtxRequest? = null,
    val response: HtxResponse? = null,
    val failure: String? = null,
) : FanoutEvent {
    override val eventType: Int = 0x485458
}

interface HtxFrameSubscriber {
    suspend fun onHtxFrames(frames: HtxFrames)
}

interface HtxRouteService : KeyedService {
    companion object Key : CoroutineContext.Key<HtxRouteService>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult
}

object HtxKey : AsyncContextKey<HtxElement>()

open class HtxElement(
    val baseUrl: String = "",
    private val routeService: HtxRouteService,
    parentJob: Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
    private val ownedRouteService: AsyncContextElement? = null,
    override val fanoutSubscribers: List<AsyncContextElement> = emptyList(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> get() = HtxKey

    private var nextExchangeOrdinal: Long = 1

    open suspend fun exchange(
        request: HtxRequest,
    ): HtxExchangeResult {
        check(state.isAtLeast(ElementState.OPEN)) {
            "HTX element must be open before request dispatch."
        }

        val normalizedRequest = request.copy(
            target = request.target.copy(
                requestPath = normalizePath(request.target.requestPath),
            ),
        )
        val exchangeState = HtxExchangeState(
            exchangeOrdinal = nextExchangeOrdinal++,
            baseUrl = baseUrl,
            lifecycle = HtxExchangeLifecycle.CREATED,
        )
        val result = routeService.exchange(exchangeState, normalizedRequest)
        channelize(result.frames)
        return result
    }

    open suspend fun request(request: HtxRequest): HtxResponse {
        val result = exchange(request)

        return result.state.response ?: error(
            result.state.failure ?: "HTX route service did not produce a response.",
        )
    }

    open suspend fun request(
        method: String,
        path: String,
        headers: HtxHeaders = emptyHtxHeaders(),
    ): HtxResponse =
        request(
            parseHtxRequest(
                url = resolveRequestUrl(path),
                method = methodFromToken(method),
            ).copy(
                headers = headers,
            ),
        )

    internal suspend fun channelize(frames: HtxFrames) {
        fanoutSubscribers
            .filterIsInstance<HtxFrameSubscriber>()
            .forEach { it.onHtxFrames(frames) }

        val subscribers = fanoutSubscribers.filterIsInstance<FanoutEventSubscriber>()
        if (subscribers.isNotEmpty()) {
            frames.toList().forEach { frame ->
                subscribers.forEach { it.onFanoutEvent(frame) }
            }
        }
    }

    override suspend fun close() {
        if (ownedRouteService != null && ownedRouteService.state.isLessThan(ElementState.CLOSED)) {
            ownedRouteService.close()
        }
        if (ownedSupervisor != null && ownedSupervisor.state.isLessThan(ElementState.CLOSED)) {
            ownedSupervisor.close()
        }
        super.close()
    }
}

suspend fun openHtxElement(
    baseUrl: String = "",
    routeService: HtxRouteService? = null,
    nioSupervisor: NioSupervisor? = null,
    parentJob: Job? = null,
    subscribers: List<AsyncContextElement> = emptyList(),
): HtxElement {
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
        ?: DefaultHtxRouteService

    return HtxElement(
        baseUrl = baseUrl,
        routeService = resolvedService,
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
        fanoutSubscribers = subscribers,
    ).also { it.open() }
}

private object DefaultHtxRouteService : HtxRouteService {
    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult {
        val response = when {
            request.target.requestPath != "/health" -> HtxResponse(404, ByteSeries("not found"))
            request.method != HtxMethod.GET -> HtxResponse(405, ByteSeries("method not allowed"))
            else -> HtxResponse(200, ByteSeries("ok"))
        }

        val responded = state.copy(
            lifecycle = HtxExchangeLifecycle.RESPONDED,
            request = request,
            response = response,
        )

        return HtxExchangeResult(
            responded,
            htxFrames(
                HtxFrame(
                    exchangeOrdinal = state.exchangeOrdinal,
                    stage = HtxFlowStage.REQUEST,
                    request = request,
                ),
                HtxFrame(
                    exchangeOrdinal = state.exchangeOrdinal,
                    stage = HtxFlowStage.RESPONSE,
                    request = request,
                    response = response,
                ),
            ),
        )
    }
}

private fun normalizePath(path: String): String =
    if (path.startsWith("/")) path else "/$path"

private fun methodFromToken(method: String): HtxMethod =
    when (method.uppercase()) {
        "GET" -> HtxMethod.GET
        "HEAD" -> HtxMethod.HEAD
        "POST" -> HtxMethod.POST
        "PUT" -> HtxMethod.PUT
        "PATCH" -> HtxMethod.PATCH
        "DELETE" -> HtxMethod.DELETE
        "OPTIONS" -> HtxMethod.OPTIONS
        else -> error("Unsupported HTX method: $method")
    }

private fun HtxElement.resolveRequestUrl(path: String): String {
    if ("://" in path) {
        return path
    }
    val normalizedPath = normalizePath(path)
    val origin = if (baseUrl.isBlank()) "http://127.0.0.1" else baseUrl.trimEnd('/')
    return origin + normalizedPath
}
