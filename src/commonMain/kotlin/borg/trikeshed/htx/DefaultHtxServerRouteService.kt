package borg.trikeshed.htx

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Series
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default implementation of [HtxServerRouteService] with in-memory routing table.
 */
class DefaultHtxServerRouteService : HtxServerRouteService {
    private val routes = mutableMapOf<Pair<HtxMethod, String>, suspend (HtxRequest) -> HtxResponse>()
    private val mutex = Mutex()

    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult {
        val routeKey = request.method to request.target.requestPath
        val handler = mutex.withLock { routes[routeKey] }
            ?: mutex.withLock { findWildcardHandler(request.method, request.target.requestPath) }

        val response = if (handler != null) {
            try {
                handler(request)
            } catch (e: Throwable) {
                HtxResponse(500, ByteSeries("Internal server error: ${e.message}"))
            }
        } else {
            when {
                request.target.requestPath != "/health" -> HtxResponse(404, ByteSeries("not found"))
                request.method != HtxMethod.GET -> HtxResponse(405, ByteSeries("method not allowed"))
                else -> HtxResponse(200, ByteSeries("ok"))
            }
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

    override fun registerRoute(method: HtxMethod, path: String, handler: suspend (HtxRequest) -> HtxResponse) {
        mutex.withLock { routes[method to path] = handler }
    }

    override fun registerAll(path: String, handler: suspend (HtxRequest) -> HtxResponse) {
        mutex.withLock {
            HtxMethod.values().forEach { method ->
                routes[method to path] = handler
            }
        }
    }

    override fun unregisterRoute(method: HtxMethod, path: String) {
        mutex.withLock { routes.remove(method to path) }
    }

    private fun findWildcardHandler(method: HtxMethod, path: String): (suspend (HtxRequest) -> HtxResponse)? {
        val wildcardKey = method to "${path.split('/').dropLast(1).joinToString('/') /* not exact - simplified */}/*"
        // Simple prefix matching for now
        return routes.entries.firstOrNull { (key, _) ->
            key.first == method && path.startsWith(key.second.removeSuffix("/*"))
        }?.value
    }

    override val key: CoroutineContext.Key<*> get() = HtxServerRouteService.Key
}