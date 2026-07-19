package borg.trikeshed.dht.routing

import borg.trikeshed.dht.include.Route
import borg.trikeshed.lib.Join
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/**
 * Event emitted when a routing table is updated.
 */
sealed class RoutingEvent<TNum : Comparable<TNum>> {
    /** Emitted when a new route is added to the routing table. */
    data class RouteAdded<TNum : Comparable<TNum>>(val route: Route<TNum>) : RoutingEvent<TNum>()

    /** Emitted when a route is evicted/removed from the routing table. */
    data class RouteEvicted<TNum : Comparable<TNum>>(val route: Route<TNum>) : RoutingEvent<TNum>()
}

/**
 * A Context Element that holds the routing table fanout state.
 * Transport emission sites can publish to this element.
 */
class RoutingEventElement<TNum : Comparable<TNum>>(
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    // Fanout channel for routing events
    val fanout = Channel<RoutingEvent<TNum>>(Channel.BUFFERED)

    suspend fun publishEvent(event: RoutingEvent<TNum>) {
        fanout.send(event)
    }

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<RoutingEventElement<*>>
}

suspend inline fun <TNum : Comparable<TNum>> publishRoutingEvent(
    context: CoroutineContext,
    event: RoutingEvent<TNum>
) {
    (context[RoutingEventElement] as? RoutingEventElement<TNum>)?.publishEvent(event)
}
