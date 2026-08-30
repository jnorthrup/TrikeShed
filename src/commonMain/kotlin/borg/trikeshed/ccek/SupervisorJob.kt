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
    /**
     * Put an arbitrary resource under this supervisor's lifecycle, so [close] releases it along
     * with everything else the supervisor holds.
     *
     * The supervisor already owned exactly this discipline — it kept the [CancelToken]s that
     * [slot] minted and cancelled them all on close — but only fanout subscriptions could reach
     * it. Anything else with a lifecycle had to keep its own cancel list, and a hand-maintained
     * cancel list is the failure mode: it works only while it stays exhaustive, and whatever is
     * added next and forgets to register leaks silently.
     *
     * That is not hypothetical here. `OroborosDaemon`'s `--once` hung forever because its shutdown
     * cancelled jobs by name and one launch was not on the list; and `GuestModules` mounted
     * `URLClassLoader`s into a static map that nothing ever closed. Registering with a supervisor
     * makes release a property of the hierarchy instead of a property of remembering.
     *
     * Registering on a CLOSED supervisor cancels the token immediately rather than retaining it:
     * a resource acquired after shutdown is a leak, not a member.
     */
    fun hold(token: CancelToken)
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

    override fun hold(token: CancelToken) {
        // A resource handed to a supervisor that is already CLOSED is not a member of anything;
        // retaining it would mean it is never released, which is the leak this method exists to
        // prevent. Cancel it now and say nothing was held.
        if (_lifecycle == FanoutLifecycle.CLOSED) token.cancel() else cancelTokens.add(token)
    }

    override fun close() {
        _lifecycle = FanoutLifecycle.CLOSED
        _fanoutSubscribers = 0
        // One cancel per token, and the list is emptied even if a token throws — a resource that
        // fails to release must not strand the ones behind it in the list.
        val held = cancelTokens.toList()
        cancelTokens.clear()
        held.forEach { runCatching { it.cancel() } }
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
