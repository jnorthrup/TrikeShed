package borg.trikeshed.windowtoolkit.context

import borg.trikeshed.windowtoolkit.internal.SignalContextElement
import borg.trikeshed.windowtoolkit.internal.SignalEvent
import borg.trikeshed.windowtoolkit.internal.SignalSubscriber
import borg.trikeshed.windowtoolkit.internal.AsyncSignalElement
import borg.trikeshed.windowtoolkit.internal.AsyncSignalKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.CoroutineContext

// -- Temporary Stubs for CCEK Reactor implementation until KMP JS issues in root project are resolved --
// TODO: Resolve build limitations with root Trikeshed project and JS compilation.
// Once Trikeshed root supports JS natively, we should import AsyncContextElement, AsyncContextKey, ElementState
// directly from borg.trikeshed.context instead of maintaining local stubs.

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
 * Now backed by user-signals SignalContextElement for signal fanout.
 */
class WindowContextElement : AsyncContextElement {
    companion object Key : AsyncContextKey<WindowContextElement>
    override val key: CoroutineContext.Key<*> get() = Key

    // Delegate to signal context for fanout
    private val signalContext = SignalContextElement()

    // Window-specific subscribers (for backward compatibility)
    private val subscribers = mutableListOf<WindowEventSubscriber>()

    override suspend fun open() {
        super.open()
        signalContext.open()
    }

    suspend fun publishResize(width: Double, height: Double) {
        val event = WindowResizeEvent(width, height)
        subscribers.forEach { it.onResize(event) }
        // Emit to signal context for fanout
        signalContext.emit("window.resize", event)
    }

    suspend fun publishToken(token: String) {
        val event = InputTokenEvent(token)
        subscribers.forEach { it.onToken(event) }
        signalContext.emit("window.token", event)
    }

    fun registerSubscriber(subscriber: WindowEventSubscriber) {
        if (!subscribers.contains(subscriber)) {
            subscribers.add(subscriber)
        }
    }

    fun unregisterSubscriber(subscriber: WindowEventSubscriber) {
        subscribers.remove(subscriber)
    }

    // Signal context integration
    /** Subscribe to all signal events (including window events) */
    fun subscribeSignals(subscriber: SignalSubscriber) = signalContext.subscribe(subscriber)

    fun unsubscribeSignals(subscriber: SignalSubscriber) = signalContext.unsubscribe(subscriber)

    /** Access underlying signal context for advanced usage */
    val signalContextInstance: SignalContextElement = signalContext

    /** Emit a custom signal through the context */
    fun <T> emitSignal(signalId: String, value: T): Boolean = signalContext.emit(signalId, value)

    /** Get a registered signal */
    fun <T> getSignal(signalId: String) = signalContext.getSignal<T>(signalId)

    override suspend fun close() {
        subscribers.clear()
        signalContext.close()
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

/**
 * Flow-based access to window events via user-signals
 */
fun WindowContextElement.resizeFlow(): Flow<WindowResizeEvent> {
    val channel = Channel<WindowResizeEvent>(Channel.UNLIMITED)
    subscribeSignals(object : SignalSubscriber {
        override fun onEvent(event: SignalEvent) {
            when (event) {
                is SignalEvent.SignalChanged<*> -> if (event.signalId == "window.resize" && event.value is WindowResizeEvent) {
                    channel.trySend(event.value as WindowResizeEvent)
                }
                else -> {}
            }
        }
    })
    return channel.receiveAsFlow()
}

fun WindowContextElement.tokenFlow(): Flow<InputTokenEvent> {
    val channel = Channel<InputTokenEvent>(Channel.UNLIMITED)
    subscribeSignals(object : SignalSubscriber {
        override fun onEvent(event: SignalEvent) {
            when (event) {
                is SignalEvent.SignalChanged<*> -> if (event.signalId == "window.token" && event.value is InputTokenEvent) {
                    channel.trySend(event.value as InputTokenEvent)
                }
                else -> {}
            }
        }
    })
    return channel.receiveAsFlow()
}