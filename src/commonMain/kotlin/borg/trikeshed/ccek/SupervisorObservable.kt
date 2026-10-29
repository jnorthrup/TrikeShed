package borg.trikeshed.ccek

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface CancelToken {
    fun cancel()
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

    /** Completion releases every held token; a token handed over after completion is released on arrival. */
    val supervisor: CompletableJob = SupervisorJob()
    private val slots = LinkedHashSet<CancelToken>()

    val fanoutSubscribers: Int
        get() = slots.size

    /** [supervisor] completes, releasing what it holds, once in-flight children finish. */
    fun drain() {
        supervisor.complete()
    }

    fun close() {
        supervisor.cancel()
    }

    fun hold(token: CancelToken): CancelToken {
        supervisor.invokeOnCompletion {
            try {
                token.cancel()
            } catch (_: Throwable) {
            }
        }
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
