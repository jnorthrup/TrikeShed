package borg.trikeshed.ccek

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface CancelToken {
    fun cancel()
}

enum class FanoutLifecycle {
    CLOSED,
    OPEN,
    DRAINING,
}

class MutableObservable<T>(initial: T) {
    private val observers = LinkedHashMap<Int, (T) -> Unit>()
    private var nextObserverId = 0

    var value: T = initial
        private set

    fun update(next: T) {
        value = next
        observers.values.toList().forEach { it(next) }
    }

    fun observe(callback: (T) -> Unit): CancelToken {
        val id = nextObserverId++
        observers[id] = callback
        callback(value)
        return object : CancelToken {
            private var cancelled = false

            override fun cancel() {
                if (cancelled) return
                cancelled = true
                observers.remove(id)
            }
        }
    }
}

class RealSupervisorJob(
    val name: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RealSupervisorJob>

    override val key: CoroutineContext.Key<*> get() = Key

    private val held = ArrayList<CancelToken>()
    private val slots = LinkedHashSet<CancelToken>()
    private var terminal = false

    var lifecycle: FanoutLifecycle = FanoutLifecycle.CLOSED
        private set

    val fanoutSubscribers: Int
        get() = slots.size

    fun open() {
        if (!terminal) lifecycle = FanoutLifecycle.OPEN
    }

    fun drain() {
        if (!terminal && lifecycle == FanoutLifecycle.OPEN) lifecycle = FanoutLifecycle.DRAINING
    }

    fun close() {
        if (terminal) return
        terminal = true
        lifecycle = FanoutLifecycle.CLOSED
        val release = held.toList()
        held.clear()
        release.forEach { token ->
            try {
                token.cancel()
            } catch (_: Throwable) {
            }
        }
        slots.clear()
    }

    fun hold(token: CancelToken): CancelToken {
        if (terminal) {
            try {
                token.cancel()
            } catch (_: Throwable) {
            }
            return token
        }
        held += token
        return token
    }

    fun <T> slot(source: MutableObservable<T>): MutableObservable<T> {
        val view = MutableObservable(source.value)
        var token: CancelToken? = null
        val slotToken = object : CancelToken {
            private var cancelled = false

            override fun cancel() {
                if (cancelled) return
                cancelled = true
                slots.remove(this)
                token?.cancel()
            }
        }
        token = source.observe { view.update(it) }
        slots += slotToken
        hold(slotToken)
        return view
    }
}
