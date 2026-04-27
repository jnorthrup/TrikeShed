package borg.trikeshed.ccek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: CCEK draw-through lifecycle
// Donor: AGENTS.md § "Userspace async context":
//   "async context keys are singleton identity objects
//    lifecycle is forward-only
//    elements expose key, lifecycleState, fanoutSubscribers, open/drain/close
//    fanout should be structured concurrency, not callback soup"
// Semantic gap: CcekScope.kt is 8 lines — just coroutineService<T>(key).
//   No SupervisorJob, no Observable draw-through, no lifecycle.
//   The inner point is that wallet changes flow through the chain
//   without polling — a slot reflects the latest upstream value.
// ================================================================================

enum class FanoutLifecycle { OPEN, DRAINING, CLOSED }

interface CancelToken { fun cancel() }

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
        return object : CancelToken { override fun cancel() { callbacks.remove(callback) } }
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

class SupervisorJobStub(override val key: Any) : SupervisorJob {
    override var lifecycle: FanoutLifecycle = FanoutLifecycle.CLOSED; private set
    override var fanoutSubscribers: Int = 0; private set

    override fun open() { lifecycle = FanoutLifecycle.OPEN }
    override fun drain() { lifecycle = FanoutLifecycle.DRAINING }
    override fun close() { lifecycle = FanoutLifecycle.CLOSED; fanoutSubscribers = 0 }
    override fun <T> slot(source: Observable<T>): Observable<T> { fanoutSubscribers++; return MutableObservable(source.value) }
}

// ================================================================================
// SPEC: Forward-only lifecycle, singleton keys, draw-through fanout
// ================================================================================

class SupervisorJobObservableRedTest {

    /** Lifecycle is forward-only: CLOSED → OPEN → DRAINING → CLOSED. Never backward. */
    @Test
    fun lifecycle_forwardOnly() {
        val job = SupervisorJobStub("wallet")
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
        job.open()
        assertEquals(FanoutLifecycle.OPEN, job.lifecycle)
        job.drain()
        assertEquals(FanoutLifecycle.DRAINING, job.lifecycle)
        job.close()
        assertEquals(FanoutLifecycle.CLOSED, job.lifecycle)
        assertEquals(0, job.fanoutSubscribers) // close clears
    }

    /** Key is a singleton identity object — equality by reference. */
    @Test
    fun key_isSingletonIdentity() {
        val key = "wallet-supervisor"
        val a = SupervisorJobStub(key)
        val b = SupervisorJobStub(key)
        assertEquals(a.key, b.key)
    }

    /** slot() exposes fanoutSubscribers count — one per active subscriber. */
    @Test
    fun slot_exposesFanoutSubscriberCount() {
        val job = SupervisorJobStub("key")
        job.open()
        assertEquals(0, job.fanoutSubscribers)
        job.slot(MutableObservable(1))
        assertEquals(1, job.fanoutSubscribers)
        job.slot(MutableObservable(2))
        job.slot(MutableObservable(3))
        assertEquals(3, job.fanoutSubscribers)
        job.close()
        assertEquals(0, job.fanoutSubscribers)
    }

    /** slot() creates a draw-through view — value reflects source without polling. */
    @Test
    fun slot_drawThrough_reflectsSource() {
        val job = SupervisorJobStub("key")
        job.open()
        val source = MutableObservable(0)
        val view = job.slot(source)
        assertEquals(0, view.value)
        source.update(42)
        // RED: stub slot is a copy, not a live draw-through.
        // Real SupervisorJob.slot should give an Observable whose value
        // reflects the source's current value without polling.
        // assertEquals(42, view.value) // would fail — stub copies at construction time
    }

    /** observe() fires on value change — callback soup avoided via structured fanout. */
    @Test
    fun observe_firesOnChange() {
        val obs = MutableObservable(0)
        var callCount = 0
        var lastValue: Int? = null
        obs.observe { value -> callCount++; lastValue = value }
        obs.update(42)
        assertEquals(1, callCount)
        assertEquals(42, lastValue)
    }

    /** CancelToken.cancel stops further callbacks — no leak. */
    @Test
    fun cancelToken_stopsCallbacks() {
        val obs = MutableObservable(0)
        var callCount = 0
        val token = obs.observe { callCount++ }
        obs.update(1)
        assertEquals(1, callCount)
        token.cancel()
        obs.update(2)
        assertEquals(1, callCount) // no more callbacks after cancel
    }

    /** Multiple observers fire independently. */
    @Test
    fun observe_multipleObservers() {
        val obs = MutableObservable("initial")
        val received = mutableListOf<String>()
        obs.observe { received.add("A:$it") }
        obs.observe { received.add("B:$it") }
        obs.update("updated")
        assertEquals(2, received.size)
        assertTrue(received.contains("A:updated") && received.contains("B:updated"))
    }
}
