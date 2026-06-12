package borg.trikeshed.usersignals

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel

interface AsyncSignalElement : CoroutineContext.Element {
    suspend fun open() {}
    suspend fun close() {}
}

interface AsyncSignalKey<E : CoroutineContext.Element> : CoroutineContext.Key<E>

sealed interface SignalEvent {
    data class SignalChanged<out T>(val signalId: String, val value: T) : SignalEvent
    data class SignalBatch(val events: List<SignalEvent>) : SignalEvent
    data class SubscriberRegistered(val subscriberId: String) : SignalEvent
    data class SubscriberUnregistered(val subscriberId: String) : SignalEvent
}

interface SignalSubscriber {
    fun onEvent(event: SignalEvent)
}

class SignalContextElement : AsyncSignalElement {
    companion object Key : AsyncSignalKey<SignalContextElement>

    override val key: CoroutineContext.Key<*> get() = Key

    private val subscribers = mutableListOf<SignalSubscriber>()
    private val signalRegistry = mutableMapOf<String, Signal<*>>()
    private val signalSources = mutableMapOf<String, SignalSource<*>>()

    override suspend fun open() {}

    fun registerSource(signalId: String, source: SignalSource<*>) {
        signalSources[signalId] = source
        signalRegistry[signalId] = source
        notifySubscribers(SignalEvent.SubscriberRegistered(signalId))
    }

    fun registerSignal(signalId: String, signal: Signal<*>) {
        signalRegistry[signalId] = signal
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getSignal(signalId: String): Signal<T>? = signalRegistry[signalId] as? Signal<T>

    @Suppress("UNCHECKED_CAST")
    fun <T> getSource(signalId: String): SignalSource<T>? = signalSources[signalId] as? SignalSource<T>

    fun <T> emit(signalId: String, value: T): Boolean {
        val source = getSource<T>(signalId)
        return if (source != null) {
            source.emit(value)
            notifySubscribers(SignalEvent.SignalChanged(signalId, value))
            true
        } else false
    }

    suspend fun <T> emitSuspend(signalId: String, value: T): Boolean {
        val source = getSource<T>(signalId)
        return if (source != null) {
            source.emitSuspend(value)
            notifySubscribers(SignalEvent.SignalChanged(signalId, value))
            true
        } else false
    }

    fun subscribe(subscriber: SignalSubscriber) {
        if (!subscribers.contains(subscriber)) subscribers.add(subscriber)
    }

    fun unsubscribe(subscriber: SignalSubscriber) {
        subscribers.remove(subscriber)
    }

    private fun notifySubscribers(event: SignalEvent) {
        subscribers.forEach { it.onEvent(event) }
    }

    fun notifyBatch(events: List<SignalEvent>) {
        subscribers.forEach { it.onEvent(SignalEvent.SignalBatch(events)) }
    }

    override suspend fun close() {
        subscribers.clear()
        signalRegistry.clear()
        signalSources.clear()
    }

    val registrySnapshot: Map<String, Any>
        get() = signalRegistry.mapValues { (_, v) -> v.value ?: Any() }
}

fun CoroutineContext.getSignalContextElement(): SignalContextElement? = this[SignalContextElement.Key]

class SignalFactory(private val context: SignalContextElement) {
    // Explicit type parameters for registration
    fun toggle(signalId: String, initial: Boolean = false): Toggle {
        val t = toggle(initial)
        context.registerSource(signalId, t as SignalSource<*>)
        return t
    }
    fun idiotLight(signalId: String, initial: Boolean = false): IdiotLight {
        val l = idiotLight(initial)
        context.registerSource(signalId, l as SignalSource<*>)
        return l
    }
    fun momentaryButton(signalId: String): MomentaryButton {
        val b = momentaryButton()
        context.registerSource(signalId, b as SignalSource<*>)
        return b
    }
    fun slider(signalId: String, min: Double, max: Double, initial: Double? = null, step: Double? = null): Slider {
        val s = slider(min, max, initial, step)
        context.registerSource(signalId, s as SignalSource<*>)
        return s
    }
    fun knob(signalId: String, min: Double = 0.0, max: Double = 1.0, initial: Double = 0.0, detents: Int? = null): Knob {
        val k = knob(min, max, initial, detents)
        context.registerSource(signalId, k as SignalSource<*>)
        return k
    }
    fun <T> dial(signalId: String, positions: List<T>, initial: T? = null): Dial<T> {
        val d = dial(positions, initial)
        context.registerSource(signalId, d as SignalSource<*>)
        return d
    }
    fun levelMeter(signalId: String, peakHoldMillis: Long = 1000): LevelMeter {
        val lm = levelMeter(peakHoldMillis)
        context.registerSource(signalId, lm as SignalSource<*>)
        return lm
    }
    fun <T> radioToggle(signalId: String, options: List<T>, initial: T? = null): RadioToggle<T> {
        val rt = radioToggle(options, initial)
        context.registerSource(signalId, rt as SignalSource<*>)
        return rt
    }
}

fun signalContext(block: SignalContextElement.() -> Unit): SignalContextElement {
    val context = SignalContextElement()
    context.block()
    return context
}

fun signalContextWithFactory(block: SignalFactory.() -> Unit): SignalContextElement {
    val context = SignalContextElement()
    val factory = SignalFactory(context)
    factory.block()
    return context
}