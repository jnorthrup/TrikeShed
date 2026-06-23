package borg.trikeshed.htx

import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/**
 * Server-side route service that allows registering HTTP handlers.
 * Extends [HtxRouteService] with route registration capabilities.
 */
interface HtxServerRouteService : HtxRouteService {
    companion object Key : CoroutineContext.Key<HtxServerRouteService>
    override val key: CoroutineContext.Key<*> get() = Key

    /**
     * Register a handler for a specific method and path.
     * Path can contain wildcards (e.g., "/api/*").
     */
    fun registerRoute(method: HtxMethod, path: String, handler: suspend (HtxRequest) -> HtxResponse)

    /**
     * Register a handler for all methods on a path.
     */
    fun registerAll(path: String, handler: suspend (HtxRequest) -> HtxResponse)

    /**
     * Remove a registered route.
     */
    fun unregisterRoute(method: HtxMethod, path: String)
}