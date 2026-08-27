package borg.trikeshed.ccek

enum class FanoutLifecycle { OPEN, DRAINING, CLOSED }

interface CancelToken {
    fun cancel()
}

interface Observable<out T> {
    val value: T
    fun observe(callback: (T) -> Unit): CancelToken
}

class MutableObservable<T>(initial: T) : Observable<T> {
    private var _value: T = initial
    override val value: T get() = _value
    private val callbacks = mutableListOf<(T) -> Unit>()

    fun update(newValue: T) {
        _value = newValue
        callbacks.toList().forEach { it(newValue) }
    }

    override fun observe(callback: (T) -> Unit): CancelToken {
        callbacks.add(callback)
        // Fire initially with the current value
        callback(_value)
        return object : CancelToken {
            override fun cancel() {
                callbacks.remove(callback)
            }
        }
    }
}

interface SupervisorJob {
    val key: Any
    val lifecycle: FanoutLifecycle
    val fanoutSubscribers: Int
    fun <T> slot(source: Observable<T>): Observable<T>
    fun open()
    fun drain()
    fun close()
}

class RealSupervisorJob(override val key: Any) : SupervisorJob {
    private var _lifecycle = FanoutLifecycle.CLOSED
    override val lifecycle: FanoutLifecycle get() = _lifecycle

    private var _fanoutSubscribers = 0
    override val fanoutSubscribers: Int get() = _fanoutSubscribers

    private val cancelTokens = mutableListOf<CancelToken>()

    override fun open() {
        if (_lifecycle == FanoutLifecycle.CLOSED) {
            _lifecycle = FanoutLifecycle.OPEN
        }
    }

    override fun drain() {
        if (_lifecycle == FanoutLifecycle.OPEN) {
            _lifecycle = FanoutLifecycle.DRAINING
        }
    }

    override fun close() {
        _lifecycle = FanoutLifecycle.CLOSED
        _fanoutSubscribers = 0
        cancelTokens.toList().forEach { it.cancel() }
        cancelTokens.clear()
    }

    override fun <T> slot(source: Observable<T>): Observable<T> {
        val dest = MutableObservable(source.value)
        val token = source.observe { newValue ->
            dest.update(newValue)
        }
        cancelTokens.add(token)
        _fanoutSubscribers++
        return dest
    }
}
