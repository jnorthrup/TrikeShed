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

    // ⚡ Bolt: Fully implement deferred modification pattern to prevent O(N) allocation trap in notification loops
    private var updateDepth = 0
    private var deferredRemovals: ArrayList<Int>? = null
    private var deferredAdds: ArrayList<Pair<Int, (T) -> Unit>>? = null

    fun update(next: T) {
        value = next
        updateDepth++
        try {
            for (observer in observers.values) {
                observer(next)
            }
        } finally {
            updateDepth--
            if (updateDepth == 0) {
                deferredAdds?.let {
                    for (i in 0 until it.size) {
                        val pair = it[i]
                        observers[pair.first] = pair.second
                    }
                    it.clear()
                }
                deferredRemovals?.let {
                    for (i in 0 until it.size) observers.remove(it[i])
                    it.clear()
                }
            }
        }
    }

    fun observe(callback: (T) -> Unit): CancelToken {
        val id = nextObserverId++
        if (updateDepth > 0) {
            if (deferredAdds == null) deferredAdds = ArrayList()
            deferredAdds!!.add(id to callback)
        } else {
            observers[id] = callback
        }
        callback(value)
        return object : CancelToken {
            private var cancelled = false

            override fun cancel() {
                if (cancelled) return
                cancelled = true
                if (updateDepth > 0) {
                    if (deferredRemovals == null) deferredRemovals = ArrayList()
                    deferredRemovals!!.add(id)
                } else {
                    observers.remove(id)
                }
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
