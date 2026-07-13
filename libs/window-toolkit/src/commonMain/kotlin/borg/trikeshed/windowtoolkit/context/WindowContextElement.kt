package borg.trikeshed.windowtoolkit.context

import kotlin.coroutines.CoroutineContext

// TODO: Resolve build limitations with root Trikeshed project and JS compilation.
// Once Trikeshed root supports JS natively, we should import AsyncContextElement, AsyncContextKey, ElementState
// directly from borg.trikeshed.context instead of maintaining local stubs.

// -- Temporary Stubs for CCEK Reactor implementation until KMP JS issues in root project are resolved --

interface AsyncContextElement : CoroutineContext.Element {
    suspend fun open() {}
    suspend fun close() {}
}

interface AsyncContextKey<E : CoroutineContext.Element> : CoroutineContext.Key<E>

// -----------------------------------------------------------------------------------------------------

data class WindowResizeEvent(val width: Double, val height: Double)
data class InputTokenEvent(val token: String)

/**
 * Reactor-Driven UI Context Element modeling the loop for the Window Toolkit.
 */
class WindowContextElement : AsyncContextElement {
    companion object Key : AsyncContextKey<WindowContextElement>
    override val key: CoroutineContext.Key<*> get() = Key

    // Subscribers array, mimicking the CCEK fanout model seen in activejs PointcutCcekElements
    private val subscribers = mutableListOf<WindowEventSubscriber>()

    // Optional fanout dispatcher hook for generic Splat metrics tracing
    // var fanoutDispatcher: FanoutDispatcherElement? = null // TODO: Uncomment when motion-estimation works on JS

    override suspend fun open() {
        super.open()
        // state = ElementState.ACTIVE // TODO
    }

    suspend fun publishResize(width: Double, height: Double) {
        val event = WindowResizeEvent(width, height)
        subscribers.forEach { it.onResize(event) }
        // fanoutDispatcher?.splatPublish(event) // TODO
    }

    suspend fun publishToken(token: String) {
        val event = InputTokenEvent(token)
        subscribers.forEach { it.onToken(event) }
        // fanoutDispatcher?.splatPublish(event) // TODO
    }

    fun registerSubscriber(subscriber: WindowEventSubscriber) {
        if (!subscribers.contains(subscriber)) {
            subscribers.add(subscriber)
        }
    }

    fun unregisterSubscriber(subscriber: WindowEventSubscriber) {
        subscribers.remove(subscriber)
    }

    override suspend fun close() {
        subscribers.clear()
        super.close()
    }
}

/**
 * Subscriber interface for window events, replacing standard Delegates.observable
 * with explicitly channelized downstream delivery matching the Trikeshed architecture.
 */
interface WindowEventSubscriber {
    fun onResize(event: WindowResizeEvent)
    fun onToken(event: InputTokenEvent)
}

// Helpers for Context Resolution
fun CoroutineContext.getWindowContextElement(): WindowContextElement? =
    this[WindowContextElement.Key]
